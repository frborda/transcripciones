package main

import (
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

type job struct {
	kind string // "audio" | "text" | "fin"
	path string
	text string
}

// Engine: graba por segmentos y sube por Telegram. Desacoplado de la UI (la UI
// lee Snapshot() en un timer y llama Start/Cut/Finish).
type Engine struct {
	cfg    Config
	micAlt string // alternative name del micrófono elegido (modo mic/ambos)

	jobs     chan job
	cutCh    chan struct{}
	finishCh chan struct{}
	finalCh  chan struct{}

	running atomic.Bool
	pend    atomic.Int64
	env     atomic.Int64
	parte   atomic.Int64

	mu       sync.Mutex
	estado   string
	segIni   time.Time
	totalIni time.Time
	segAct   *segmento // captura en curso (para poder matarla al salir)

	onFinal func() // se llama cuando terminó de subir todo (tras "fin")
}

func NewEngine(cfg Config, micAlt string) *Engine {
	return &Engine{
		cfg: cfg, micAlt: micAlt,
		jobs:     make(chan job, 256),
		cutCh:    make(chan struct{}, 4),
		finishCh: make(chan struct{}, 1),
		finalCh:  make(chan struct{}),
		estado:   "detenido",
	}
}

func (e *Engine) setEstado(s string) { e.mu.Lock(); e.estado = s; e.mu.Unlock(); estadoTxt = s }

// getEstado lee el estado bajo el mismo mutex que lo escribe (evita la carrera de
// datos al leerlo desde la goroutine del uploader).
func (e *Engine) getEstado() string { e.mu.Lock(); defer e.mu.Unlock(); return e.estado }

// Kill corta YA la captura en curso (mata ffmpeg) para "Salir" sin dejar un
// proceso grabando huérfano. No sube lo pendiente.
func (e *Engine) Kill() {
	e.mu.Lock()
	seg := e.segAct
	e.mu.Unlock()
	if seg != nil && seg.cmd != nil && seg.cmd.Process != nil {
		_ = seg.cmd.Process.Kill()
	}
}

type Snap struct {
	Running          bool
	Estado           string
	Parte            int
	Enviadas, Pend   int64
	SegDur, TotalDur time.Duration
}

func (e *Engine) Snapshot() Snap {
	e.mu.Lock()
	defer e.mu.Unlock()
	s := Snap{
		Running: e.running.Load(), Estado: e.estado,
		Parte: int(e.parte.Load()), Enviadas: e.env.Load(), Pend: e.pend.Load(),
	}
	if e.running.Load() {
		s.SegDur = time.Since(e.segIni)
		s.TotalDur = time.Since(e.totalIni)
	}
	return s
}

func (e *Engine) Cut() {
	select {
	case e.cutCh <- struct{}{}:
	default:
	}
}

func (e *Engine) Finish() {
	select {
	case e.finishCh <- struct{}{}:
	default:
	}
}

func (e *Engine) Start() {
	if e.running.Swap(true) {
		return
	}
	e.mu.Lock()
	e.totalIni = time.Now()
	e.mu.Unlock()
	go e.uploader()
	go e.recordLoop()
}

func (e *Engine) recordLoop() {
	intervalo := parseHMS(e.cfg.Intervalo)

	est := cargarEstado()
	sesion := est.Sesion
	parte := est.Parte
	resumir := est.Activa && sesion != "" && sesionReciente(sesion)
	if resumir {
		e.setEstado(fmt.Sprintf("retomando sesión (parte %d)", parte))
		for _, p := range partesPendientes(sesion) {
			e.pend.Add(1)
			e.jobs <- job{kind: "audio", path: p}
		}
	} else {
		archivarHuerfanas()
		sesion = time.Now().Format("20060102-150405")
		parte = 0
		guardarEstado(Estado{Sesion: sesion, Parte: parte, Activa: true})
		e.jobs <- job{kind: "text", text: "inicio"}
	}

	// tope duro por parte (50 MB de Telegram); el corte real lo decide el
	// intervalo + primer silencio.
	segMax := 45 * 60
	// si no aparece un silencio en este margen tras cumplir el intervalo, corta igual.
	const margenSilencio = 30 * time.Second

	finalizando := false
	for {
		parte++
		e.parte.Store(int64(parte))
		ruta := segPath(sesion, parte)
		guardarEstado(Estado{Sesion: sesion, Parte: parte, Activa: true})
		e.mu.Lock()
		e.segIni = time.Now()
		e.mu.Unlock()

		seg, err := iniciarSegmento(e.cfg.Fuente, e.micAlt, ruta, segMax)
		if err != nil {
			// mic ocupado/desconectado: reintentar, pero sin quedar sordos a Finalizar
			// (antes el usuario no podía cerrar la sesión mientras fallaba la captura).
			e.setEstado("ERROR captura (reintentando): " + err.Error())
			select {
			case <-time.After(2 * time.Second):
				parte-- // el intento fallido no consume número de parte
				continue
			case <-e.finishCh:
				e.jobs <- job{kind: "text", text: "fin"}
				e.jobs <- job{kind: "fin"}
				return
			}
		}
		e.mu.Lock()
		e.segAct = seg
		e.mu.Unlock()
		e.setEstado(fmt.Sprintf("grabando parte %d", parte))

		// auto: a los 'intervalo' segundos se "arma" y corta en el primer silencio
		var armarCh <-chan time.Time
		if e.cfg.Auto {
			armarCh = time.After(time.Duration(intervalo) * time.Second)
		}
		var topeCh <-chan time.Time
		armado := false
		falloCaptura := false

	espera:
		for {
			select {
			case <-seg.exited: // tope -t
				break espera
			case <-seg.fault: // la captura de escritorio (WASAPI) se cayó
				falloCaptura = true
				break espera
			case <-armarCh:
				armado = true
				armarCh = nil
				topeCh = time.After(margenSilencio)
				e.setEstado(fmt.Sprintf("grabando parte %d (cortando en la próxima pausa)", parte))
			case <-seg.silencio:
				if armado {
					break espera
				}
			case <-topeCh: // se cumplió el margen sin pausa: cortar igual
				break espera
			case <-e.cutCh:
				break espera
			case <-e.finishCh:
				finalizando = true
				break espera
			}
		}
		seg.cerrar()
		e.mu.Lock()
		e.segAct = nil
		e.mu.Unlock()

		if archivoOK(ruta) {
			e.pend.Add(1)
			e.jobs <- job{kind: "audio", path: ruta}
		}
		if finalizando {
			e.jobs <- job{kind: "text", text: "fin"}
			e.jobs <- job{kind: "fin"}
			return
		}
		if falloCaptura {
			// el próximo segmento reinicializa WASAPI con el dispositivo actual; un
			// respiro corto evita martillar si el dispositivo desapareció de verdad.
			e.setEstado("reiniciando captura de escritorio (cambió el dispositivo de audio)")
			time.Sleep(time.Second)
		}
	}
}

func (e *Engine) uploader() {
	for j := range e.jobs {
		switch j.kind {
		case "audio":
			if conReintento(filepath.Base(j.path), func() error {
				return sendDocument(e.cfg.BotToken, e.cfg.ChatID, j.path)
			}) {
				_ = os.Rename(j.path, filepath.Join(filepath.Dir(j.path), "ok_"+filepath.Base(j.path)))
				e.env.Add(1)
				if !strings.HasPrefix(e.getEstado(), "grabando") {
					e.setEstado("subiendo lo pendiente...")
				}
			} else {
				// error permanente (token/chat/tamaño): apartar el archivo y seguir la
				// cola para no bloquear el "fin"; el estado ya muestra el motivo.
				_ = os.Rename(j.path, filepath.Join(filepath.Dir(j.path), "fallo_"+filepath.Base(j.path)))
			}
			e.pend.Add(-1)
		case "text":
			conReintento("texto "+j.text, func() error {
				return sendMessage(e.cfg.BotToken, e.cfg.ChatID, j.text)
			})
		case "fin":
			guardarEstado(Estado{Activa: false})
			e.running.Store(false)
			e.setEstado("listo: todo enviado, la PC está procesando")
			if e.onFinal != nil {
				e.onFinal()
			}
			close(e.finalCh)
			return
		}
	}
}

// ---- helpers de archivos/sesión ----

func dirGrab() string {
	d := filepath.Join(dirBase(), grabDir)
	_ = os.MkdirAll(d, 0755)
	return d
}

func segPath(sesion string, parte int) string {
	return filepath.Join(dirGrab(), fmt.Sprintf("reunion_%s_p%03d.m4a", sesion, parte))
}

func archivoOK(ruta string) bool {
	fi, err := os.Stat(ruta)
	return err == nil && fi.Size() > 0
}

func sesionReciente(sesion string) bool {
	d := dirGrab()
	ents, _ := os.ReadDir(d)
	for _, en := range ents {
		if strings.Contains(en.Name(), "reunion_"+sesion+"_p") {
			if fi, err := en.Info(); err == nil && time.Since(fi.ModTime()) < 6*time.Hour {
				return true
			}
		}
	}
	return false
}

func partesPendientes(sesion string) []string {
	d := dirGrab()
	ents, _ := os.ReadDir(d)
	var out []string
	pre := "reunion_" + sesion + "_p"
	for _, en := range ents {
		n := en.Name()
		if strings.HasPrefix(n, pre) && strings.HasSuffix(n, ".m4a") {
			out = append(out, filepath.Join(d, n))
		}
	}
	sort.Strings(out)
	return out
}

func archivarHuerfanas() {
	d := dirGrab()
	ents, _ := os.ReadDir(d)
	huer := filepath.Join(d, "huerfanas")
	for _, en := range ents {
		n := en.Name()
		if !en.IsDir() && strings.HasPrefix(n, "reunion_") && strings.HasSuffix(n, ".m4a") {
			_ = os.MkdirAll(huer, 0755)
			_ = os.Rename(filepath.Join(d, n), filepath.Join(huer, n))
		}
	}
}
