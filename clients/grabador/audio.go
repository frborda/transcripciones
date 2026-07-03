package main

import (
	"bufio"
	"fmt"
	"io"
	"os/exec"
	"regexp"
	"runtime"
	"strings"
	"sync"
	"time"
	"unsafe"

	ole "github.com/go-ole/go-ole"
	"github.com/moutend/go-wca/pkg/wca"
)

// filtro de detección de silencio: pausa de 0.5 s por debajo de -30 dB.
const silenceFilter = "silencedetect=noise=-30dB:d=0.5"

// Modos de fuente de audio.
const (
	FuenteMic     = "mic"
	FuenteDesktop = "desktop"
	FuenteAmbos   = "ambos"
)

type MicDev struct {
	Nombre string // nombre amigable (para mostrar)
	Alt    string // alternative name de dshow (estable, sin acentos)
}

// enumMics lista los micrófonos (dispositivos de captura dshow).
func enumMics() []MicDev {
	cmd := exec.Command("ffmpeg", "-hide_banner", "-list_devices", "true", "-f", "dshow", "-i", "dummy")
	ocultarConsola(cmd)
	out, _ := cmd.CombinedOutput()
	lineas := strings.Split(string(out), "\n")
	reNombre := regexp.MustCompile(`"([^"]+)"\s*\(audio\)`)
	reAlt := regexp.MustCompile(`Alternative name\s*"([^"]+)"`)
	var devs []MicDev
	for i, ln := range lineas {
		if m := reNombre.FindStringSubmatch(ln); m != nil {
			d := MicDev{Nombre: m[1], Alt: m[1]}
			if i+1 < len(lineas) {
				if a := reAlt.FindStringSubmatch(lineas[i+1]); a != nil {
					d.Alt = a[1]
				}
			}
			devs = append(devs, d)
		}
	}
	return devs
}

// formatoLoopback consulta el formato del dispositivo de reproducción por defecto
// (lo que se usa para la captura loopback del escritorio).
func formatoLoopback() (rate, ch, bits int, err error) {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	if e := ole.CoInitializeEx(0, ole.COINIT_MULTITHREADED); e != nil {
		return 0, 0, 0, e
	}
	defer ole.CoUninitialize()
	var de *wca.IMMDeviceEnumerator
	if e := wca.CoCreateInstance(wca.CLSID_MMDeviceEnumerator, 0, wca.CLSCTX_ALL, wca.IID_IMMDeviceEnumerator, &de); e != nil {
		return 0, 0, 0, e
	}
	defer de.Release()
	var mmd *wca.IMMDevice
	if e := de.GetDefaultAudioEndpoint(wca.ERender, wca.EConsole, &mmd); e != nil {
		return 0, 0, 0, e
	}
	defer mmd.Release()
	var ac *wca.IAudioClient
	if e := mmd.Activate(wca.IID_IAudioClient, wca.CLSCTX_ALL, nil, &ac); e != nil {
		return 0, 0, 0, e
	}
	defer ac.Release()
	var wfx *wca.WAVEFORMATEX
	if e := ac.GetMixFormat(&wfx); e != nil {
		return 0, 0, 0, e
	}
	return int(wfx.NSamplesPerSec), int(wfx.NChannels), int(wfx.WBitsPerSample), nil
}

// segmento: una captura de audio a un archivo m4a. Se cierra con cerrar().
type segmento struct {
	cmd      *exec.Cmd
	stdin    io.WriteCloser
	modo     string
	stopLoop chan struct{}
	loopDone chan struct{}
	exited   chan struct{}      // se cierra cuando ffmpeg termina
	silencio chan struct{}      // emite en cada inicio de silencio detectado
	fault    chan struct{}      // emite si la captura de escritorio (WASAPI) se cae
	waitErr  error
	stopOnce sync.Once
}

// marcarFallo avisa (sin bloquear) que la captura loopback se cortó por un error de
// dispositivo, para que el engine recorte el segmento y reinicie la captura.
func (s *segmento) marcarFallo() {
	select {
	case s.fault <- struct{}{}:
	default:
	}
}

// iniciarSegmento arranca la captura del segmento según el modo.
func iniciarSegmento(modo, micAlt, ruta string, maxSeg int) (*segmento, error) {
	s := &segmento{modo: modo, stopLoop: make(chan struct{}), loopDone: make(chan struct{}),
		silencio: make(chan struct{}, 1), fault: make(chan struct{}, 1)}

	var args []string
	// loglevel info (no error) para que silencedetect emita sus mensajes; -nostats
	// evita el spam de progreso. Los leemos en scanSilencio.
	base := []string{"-hide_banner", "-loglevel", "info", "-nostats", "-y"}
	usaLoop := modo == FuenteDesktop || modo == FuenteAmbos

	var rate, ch, bits int
	if usaLoop {
		var err error
		rate, ch, bits, err = formatoLoopback()
		if err != nil {
			return nil, fmt.Errorf("loopback: %w", err)
		}
	}
	fmtPCM := "f32le"
	if usaLoop && bits == 16 {
		fmtPCM = "s16le"
	}

	switch modo {
	case FuenteMic:
		args = append(base,
			"-f", "dshow", "-i", "audio="+micAlt,
			"-af", silenceFilter,
			"-ac", "1", "-ar", "44100", "-c:a", "aac", "-b:a", "96k",
			"-t", fmt.Sprintf("%d", maxSeg), ruta)
	case FuenteDesktop:
		args = append(base,
			"-f", fmtPCM, "-ar", fmt.Sprintf("%d", rate), "-ac", fmt.Sprintf("%d", ch), "-i", "pipe:0",
			"-af", silenceFilter,
			"-ac", "1", "-ar", "44100", "-c:a", "aac", "-b:a", "96k",
			"-t", fmt.Sprintf("%d", maxSeg), ruta)
	case FuenteAmbos:
		args = append(base,
			"-f", "dshow", "-i", "audio="+micAlt,
			"-f", fmtPCM, "-ar", fmt.Sprintf("%d", rate), "-ac", fmt.Sprintf("%d", ch), "-i", "pipe:0",
			"-filter_complex", "[0:a][1:a]amix=inputs=2:duration=shortest:dropout_transition=0,"+silenceFilter+"[a]",
			"-map", "[a]", "-ac", "1", "-ar", "44100", "-c:a", "aac", "-b:a", "96k",
			"-t", fmt.Sprintf("%d", maxSeg), ruta)
	}

	cmd := exec.Command("ffmpeg", args...)
	ocultarConsola(cmd)
	stdin, err := cmd.StdinPipe()
	if err != nil {
		return nil, err
	}
	stderr, err := cmd.StderrPipe()
	if err != nil {
		return nil, err
	}
	s.cmd = cmd
	s.stdin = stdin
	if err := cmd.Start(); err != nil {
		return nil, err
	}
	s.exited = make(chan struct{})
	go func() { s.waitErr = s.cmd.Wait(); close(s.exited) }()
	go s.scanSilencio(stderr)

	if usaLoop {
		go s.feedLoopback(stdin)
	} else {
		close(s.loopDone) // no hay loopback en modo mic
	}
	return s, nil
}

// scanSilencio lee el stderr de ffmpeg y avisa cada vez que silencedetect reporta
// el inicio de un silencio (para cortar ahí y no en medio de una frase).
func (s *segmento) scanSilencio(r io.Reader) {
	sc := bufio.NewScanner(r)
	for sc.Scan() {
		if strings.Contains(sc.Text(), "silence_start") {
			select {
			case s.silencio <- struct{}{}:
			default:
			}
		}
	}
}

// feedLoopback captura el audio del escritorio (WASAPI loopback) y lo escribe como
// PCM crudo al stdin de ffmpeg, hasta que se pide detener.
func (s *segmento) feedLoopback(w io.Writer) {
	// Cualquier salida que NO sea un cierre limpio (stopLoop) ni el fin normal de
	// ffmpeg se trata como fallo de dispositivo: antes se volvía en silencio y el
	// resto del segmento quedaba mudo sin aviso. Ahora se avisa al engine para que
	// recorte y reinicie la captura (el dispositivo por defecto pudo cambiar).
	limpio := false
	defer func() {
		close(s.loopDone)
		if !limpio {
			s.marcarFallo()
		}
	}()
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	if ole.CoInitializeEx(0, ole.COINIT_MULTITHREADED) != nil {
		return
	}
	defer ole.CoUninitialize()

	var de *wca.IMMDeviceEnumerator
	if wca.CoCreateInstance(wca.CLSID_MMDeviceEnumerator, 0, wca.CLSCTX_ALL, wca.IID_IMMDeviceEnumerator, &de) != nil {
		return
	}
	defer de.Release()
	var mmd *wca.IMMDevice
	if de.GetDefaultAudioEndpoint(wca.ERender, wca.EConsole, &mmd) != nil {
		return
	}
	defer mmd.Release()
	var ac *wca.IAudioClient
	if mmd.Activate(wca.IID_IAudioClient, wca.CLSCTX_ALL, nil, &ac) != nil {
		return
	}
	defer ac.Release()
	var wfx *wca.WAVEFORMATEX
	if ac.GetMixFormat(&wfx) != nil {
		return
	}
	if ac.Initialize(wca.AUDCLNT_SHAREMODE_SHARED, wca.AUDCLNT_STREAMFLAGS_LOOPBACK, 200*10000, 0, wfx, nil) != nil {
		return
	}
	var acc *wca.IAudioCaptureClient
	if ac.GetService(wca.IID_IAudioCaptureClient, &acc) != nil {
		return
	}
	defer acc.Release()
	if ac.Start() != nil {
		return
	}
	defer ac.Stop()

	block := int(wfx.NBlockAlign)
	for {
		select {
		case <-s.stopLoop:
			limpio = true // cierre pedido por cerrar(): salida normal, no es fallo
			return
		default:
		}
		var pkt uint32
		if acc.GetNextPacketSize(&pkt) != nil {
			return
		}
		for pkt > 0 {
			var data *byte
			var frames, flags uint32
			if acc.GetBuffer(&data, &frames, &flags, nil, nil) != nil {
				return
			}
			n := int(frames) * block
			if data != nil && n > 0 {
				b := unsafe.Slice(data, n)
				if _, err := w.Write(b); err != nil {
					// ffmpeg cerró su stdin (corte normal o tope -t): no es fallo de
					// dispositivo, no reiniciar la captura por esto.
					limpio = true
					acc.ReleaseBuffer(frames)
					return
				}
			}
			acc.ReleaseBuffer(frames)
			if acc.GetNextPacketSize(&pkt) != nil {
				return
			}
		}
		time.Sleep(8 * time.Millisecond)
	}
}

// cerrar detiene el segmento (cualquiera sea el motivo) y deja el m4a finalizado.
// Es idempotente y sirve tanto si ffmpeg ya salió solo (por -t) como si lo cortamos.
func (s *segmento) cerrar() {
	s.stopOnce.Do(func() {
		if s.modo == FuenteMic {
			_, _ = s.stdin.Write([]byte("q\n")) // stdin libre: 'q' finaliza ffmpeg
		} else {
			close(s.stopLoop) // frenar el feeder del loopback
		}
	})
	<-s.loopDone // esperar a que el feeder termine (mic: ya cerrado)
	if s.modo != FuenteMic {
		_ = s.stdin.Close() // EOF en pipe:0 -> ffmpeg finaliza el contenedor
	}
	<-s.exited // esperar el fin real de ffmpeg
}
