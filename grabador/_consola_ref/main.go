// Grabador de reuniones (.exe) — equivalente de escritorio de la app Android.
//
// Graba el micrófono por partes y las sube a Telegram integrándose con el modo
// incremental del watcher de la PC:
//   - al iniciar manda "inicio" (abre la sesión en la PC),
//   - cada corte (manual o automático cada hh:mm:ss) cierra un segmento y lo sube
//     SIN frenar la grabación,
//   - al finalizar sube lo último y manda "fin": la PC une todo y entrega los PDFs.
//
// Cola de subidas FIFO con reintentos (el "fin" sale después de la última parte)
// y retome automático si el proceso se cae a mitad de reunión.
//
// Requiere ffmpeg en el PATH. Audio vía DirectShow (-f dshow).
package main

import (
	"bufio"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"sort"
	"strings"
	"sync/atomic"
	"syscall"
	"time"
)

var errNoConfig = errors.New("no config")

// ---- estado global para la línea de estado en consola ----
var (
	estadoTxt  = "detenido"
	pendientes int64 // partes en cola de subida
	enviadas   int64
)

// ---- trabajos de la cola de subida ----
type job struct {
	kind string // "audio" | "text" | "fin"
	path string
	text string
}

var (
	jobs     = make(chan job, 256)
	finalCh  = make(chan struct{})
	cutCh    = make(chan struct{}, 4)
	finishCh = make(chan struct{}, 1)
)

func main() {
	cfg, err := cargarConfig()
	if err == errNoConfig {
		fmt.Println("Creé", configName, "— completá bot_token (y chat_id si no es el grupo por defecto) y volvé a ejecutar.")
		fmt.Println("Ruta:", configPath())
		pausa()
		return
	} else if err != nil {
		fmt.Println("Error leyendo config:", err)
		pausa()
		return
	}
	if strings.TrimSpace(cfg.BotToken) == "" {
		fmt.Println("Falta bot_token en", configPath())
		pausa()
		return
	}
	if _, err := exec.LookPath("ffmpeg"); err != nil {
		fmt.Println("No encuentro ffmpeg en el PATH. Instalalo o agregá C:\\ffmpeg\\bin al PATH.")
		pausa()
		return
	}
	bot, err := getMe(cfg.BotToken)
	if err != nil {
		fmt.Println("No pude validar el token:", err)
		pausa()
		return
	}

	device := cfg.Device
	nombreDisp := device
	if device == "" {
		nombreDisp, device = detectarDispositivo()
		if device == "" {
			fmt.Println("No detecté ningún micrófono (dshow). Conectá uno o seteá 'device' en la config.")
			pausa()
			return
		}
	}

	intervalo := parseHMS(cfg.Intervalo)
	fmt.Printf("Grabador listo. Bot @%s · chat %s · micrófono: %s\n", bot, cfg.ChatID, nombreDisp)
	fmt.Printf("Auto-corte: %s · cada %s\n", siNo(cfg.Auto), fmtHMS(intervalo))
	fmt.Println("Comandos:  [Enter] o c = cortar y enviar | f = finalizar y procesar | q = salir (retomable)")
	fmt.Println(strings.Repeat("-", 64))

	go uploader(cfg)
	go leerComandos()
	go signalHandler()
	go statusLoop()

	grabar(cfg, device, intervalo)

	// esperar a que la cola termine (tras "fin" o "q")
	<-finalCh
	fmt.Println("\nListo. Podés cerrar esta ventana.")
}

// grabar maneja el ciclo de segmentos. Mantiene la secuencia y no pierde partes.
func grabar(cfg Config, device string, intervalo int) {
	est := cargarEstado()
	sesion := est.Sesion
	parte := est.Parte
	resumir := est.Activa && sesion != "" && sesionReciente(sesion)

	if resumir {
		fmt.Printf("Retomando sesión interrumpida (%s, parte %d). Reenvío lo pendiente.\n", sesion, parte)
		for _, p := range partesPendientes(sesion) {
			atomic.AddInt64(&pendientes, 1)
			jobs <- job{kind: "audio", path: p}
		}
	} else {
		archivarHuerfanas()
		sesion = time.Now().Format("20060102-150405")
		parte = 0
		guardarEstado(Estado{Sesion: sesion, Parte: parte, Activa: true})
		jobs <- job{kind: "text", text: "inicio"}
	}

	// tope duro de segmento (evita superar el límite de 50 MB de Telegram aunque
	// auto esté apagado): 45 min ≈ 31 MB a 96 kbps.
	segMax := 45 * 60
	if cfg.Auto && intervalo < segMax {
		segMax = intervalo
	}

	finalizando := false
	for {
		parte++
		ruta := segPath(sesion, parte)
		guardarEstado(Estado{Sesion: sesion, Parte: parte, Activa: true})
		segIni = time.Now()
		parteActual = parte

		cmd, err := ffmpegSegmento(device, ruta, segMax)
		if err != nil {
			estadoTxt = "ERROR ffmpeg: " + err.Error()
			time.Sleep(2 * time.Second)
			continue
		}
		estadoTxt = fmt.Sprintf("grabando parte %d", parte)

		done := make(chan error, 1)
		go func() { done <- cmd.Wait() }()

		var autoTimer <-chan time.Time
		if cfg.Auto {
			autoTimer = time.After(time.Duration(intervalo) * time.Second)
		}

		motivo := ""
		select {
		case <-done: // llegó al tope -t por sí solo
			motivo = "auto"
		case <-autoTimer:
			detenerFfmpeg(cmd)
			<-done
			motivo = "auto"
		case <-cutCh:
			detenerFfmpeg(cmd)
			<-done
			motivo = "cut"
		case <-finishCh:
			detenerFfmpeg(cmd)
			<-done
			motivo = "finish"
			finalizando = true
		}
		_ = motivo

		if archivoOK(ruta) {
			atomic.AddInt64(&pendientes, 1)
			jobs <- job{kind: "audio", path: ruta}
		}

		if finalizando {
			jobs <- job{kind: "text", text: "fin"}
			jobs <- job{kind: "fin"}
			return
		}
	}
}

// uploader consume la cola en orden: sube cada audio (con reintentos) antes de
// pasar al siguiente, así el "fin" siempre llega después de la última parte.
func uploader(cfg Config) {
	for j := range jobs {
		switch j.kind {
		case "audio":
			conReintento(filepath.Base(j.path), func() error {
				return sendDocument(cfg.BotToken, cfg.ChatID, j.path)
			})
			_ = os.Rename(j.path, filepath.Join(filepath.Dir(j.path), "ok_"+filepath.Base(j.path)))
			atomic.AddInt64(&pendientes, -1)
			atomic.AddInt64(&enviadas, 1)
			if !strings.HasPrefix(estadoTxt, "grabando") {
				estadoTxt = "subiendo lo pendiente..."
			}
		case "text":
			conReintento("texto "+j.text, func() error {
				return sendMessage(cfg.BotToken, cfg.ChatID, j.text)
			})
		case "fin":
			guardarEstado(Estado{Activa: false})
			estadoTxt = "listo: todo enviado, la PC está procesando"
			close(finalCh)
			return
		}
	}
}

// ---- control por consola ----
func leerComandos() {
	sc := bufio.NewScanner(os.Stdin)
	for sc.Scan() {
		switch strings.TrimSpace(strings.ToLower(sc.Text())) {
		case "", "c", "cortar":
			select {
			case cutCh <- struct{}{}:
			default:
			}
		case "f", "fin", "finalizar":
			select {
			case finishCh <- struct{}{}:
			default:
			}
		case "q", "salir":
			fmt.Println("Saliendo (sesión queda retomable; volvé a abrir para continuar).")
			os.Exit(0)
		}
	}
}

func signalHandler() {
	c := make(chan os.Signal, 1)
	signal.Notify(c, os.Interrupt, syscall.SIGTERM)
	<-c
	fmt.Println("\nInterrumpido. La sesión queda retomable.")
	os.Exit(0)
}

// ---- línea de estado ----
var (
	segIni      = time.Now()
	parteActual int
	totalIni    = time.Now()
)

func statusLoop() {
	t := time.NewTicker(time.Second)
	for range t.C {
		p := atomic.LoadInt64(&pendientes)
		e := atomic.LoadInt64(&enviadas)
		linea := fmt.Sprintf("\r[parte %d  %s | total %s] %s · enviadas %d · en cola %d        ",
			parteActual, fmtDur(time.Since(segIni)), fmtDur(time.Since(totalIni)), estadoTxt, e, p)
		fmt.Print(linea)
	}
}

func fmtDur(d time.Duration) string {
	s := int(d.Seconds())
	if s >= 3600 {
		return fmt.Sprintf("%d:%02d:%02d", s/3600, (s%3600)/60, s%60)
	}
	return fmt.Sprintf("%d:%02d", s/60, s%60)
}

// ---- helpers de sesión/partes ----
func sesionReciente(sesion string) bool {
	for _, p := range partesPendientes(sesion) {
		if fi, err := os.Stat(p); err == nil && time.Since(fi.ModTime()) < 6*time.Hour {
			return true
		}
	}
	// también vale si hay partes ya enviadas recientes
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

// partesPendientes: partes de la sesión que aún no se subieron (sin prefijo ok_).
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

// archivarHuerfanas: partes sin subir de sesiones viejas no van a una reunión nueva.
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

func siNo(b bool) string {
	if b {
		return "ON"
	}
	return "OFF"
}

func pausa() {
	fmt.Print("\nEnter para salir...")
	bufio.NewReader(os.Stdin).ReadString('\n')
}
