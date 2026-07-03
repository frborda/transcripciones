// Grabador de reuniones (GUI Windows) — equivalente de escritorio del APK.
//
// Ventana nativa con minimizado a la bandeja del sistema (junto al reloj) y dos
// fuentes de audio: micrófono (elegible) y escritorio (todo lo que suena en
// Windows, vía WASAPI loopback), o ambos mezclados. Graba por partes y las sube a
// Telegram integrándose con el modo incremental del watcher de la PC.
package main

import (
	"bytes"
	_ "embed"
	"fmt"
	"image/png"
	"strings"
	"time"

	"github.com/lxn/walk"
	. "github.com/lxn/walk/declarative"
	"github.com/lxn/win"
)

//go:embed app.png
var appPNG []byte

// iconoApp arma el ícono de la app desde el PNG embebido; si falla, usa el stock.
func iconoApp() *walk.Icon {
	if im, err := png.Decode(bytes.NewReader(appPNG)); err == nil {
		if ico, err := walk.NewIconFromImageForDPI(im, 96); err == nil {
			return ico
		}
	}
	hicon := win.LoadIcon(0, win.MAKEINTRESOURCE(uintptr(32512)))
	ico, _ := walk.NewIconFromHICON(win.HICON(hicon))
	return ico
}

var fuentes = []struct {
	label string
	val   string
}{
	{"Micrófono", FuenteMic},
	{"Escritorio (lo que suena en Windows)", FuenteDesktop},
	{"Ambos (micrófono + escritorio)", FuenteAmbos},
}

func main() {
	cfg, _ := cargarConfig() // si no existe, devuelve defaults + crea plantilla
	mics := enumMics()
	micNames := make([]string, 0, len(mics))
	for _, m := range mics {
		micNames = append(micNames, m.Nombre)
	}
	if len(micNames) == 0 {
		micNames = []string{"(no se detectó micrófono)"}
	}

	var (
		mw       *walk.MainWindow
		srcCB    *walk.ComboBox
		micCB    *walk.ComboBox
		tokenLE  *walk.LineEdit
		chatLE   *walk.LineEdit
		ivalLE   *walk.LineEdit
		autoCB   *walk.CheckBox
		statusLB *walk.Label
		partsLB  *walk.Label
		btnStart *walk.PushButton
		btnCut   *walk.PushButton
		btnFin   *walk.PushButton
	)
	var eng *Engine

	srcIdx := 0
	for i, f := range fuentes {
		if f.val == cfg.Fuente {
			srcIdx = i
		}
	}

	refrescar := func() {
		corriendo := eng != nil && eng.running.Load()
		btnStart.SetEnabled(!corriendo)
		btnCut.SetEnabled(corriendo)
		btnFin.SetEnabled(corriendo)
		srcCB.SetEnabled(!corriendo)
		micCB.SetEnabled(!corriendo && srcCB.CurrentIndex() != 1)
		if eng == nil {
			statusLB.SetText("Estado: detenido")
			partsLB.SetText("")
			return
		}
		s := eng.Snapshot()
		statusLB.SetText("Estado: " + s.Estado)
		if s.Running {
			partsLB.SetText(fmt.Sprintf("Parte %d  ·  %s (parte) / %s (total)  ·  enviadas %d  ·  en cola %d",
				s.Parte, fmtDur(s.SegDur), fmtDur(s.TotalDur), s.Enviadas, s.Pend))
		} else {
			partsLB.SetText(fmt.Sprintf("enviadas %d  ·  en cola %d", s.Enviadas, s.Pend))
		}
	}

	onIniciar := func() {
		if eng != nil && eng.running.Load() {
			return // ya está grabando: ignorar el doble clic (WM_COMMAND encolado)
		}
		token := strings.TrimSpace(tokenLE.Text())
		chat := strings.TrimSpace(chatLE.Text())
		if token == "" || chat == "" {
			walk.MsgBox(mw, "Faltan datos", "Completá el token del bot y el chat id.", walk.MsgBoxIconWarning)
			return
		}
		fuente := fuentes[srcCB.CurrentIndex()].val
		micAlt := ""
		if fuente != FuenteDesktop {
			if len(mics) == 0 {
				walk.MsgBox(mw, "Sin micrófono", "No detecté ningún micrófono. Elegí 'Escritorio' o conectá uno.", walk.MsgBoxIconWarning)
				return
			}
			idx := micCB.CurrentIndex()
			if idx < 0 || idx >= len(mics) {
				idx = 0
			}
			micAlt = mics[idx].Alt
		}
		cfg.BotToken = token
		cfg.ChatID = chat
		cfg.Intervalo = strings.TrimSpace(ivalLE.Text())
		cfg.Auto = autoCB.Checked()
		cfg.Fuente = fuente
		cfg.Device = micAlt
		_ = guardarConfig(cfg)

		eng = NewEngine(cfg, micAlt)
		eng.onFinal = func() { mw.Synchronize(refrescar) }
		eng.Start()
		refrescar()
	}

	if err := (MainWindow{
		AssignTo: &mw,
		Title:    "Grabador de reuniones",
		MinSize:  Size{Width: 460, Height: 360},
		Size:     Size{Width: 460, Height: 380},
		Layout:   VBox{MarginsZero: false},
		Children: []Widget{
			Label{Text: "Fuente de audio:"},
			ComboBox{AssignTo: &srcCB, Model: fuenteLabels(), CurrentIndex: srcIdx,
				OnCurrentIndexChanged: func() { micCB.SetEnabled(srcCB.CurrentIndex() != 1) }},
			Label{Text: "Micrófono:"},
			ComboBox{AssignTo: &micCB, Model: micNames, CurrentIndex: 0},
			Composite{
				Layout: Grid{Columns: 2, MarginsZero: true},
				Children: []Widget{
					Label{Text: "Token del bot:"},
					LineEdit{AssignTo: &tokenLE, Text: cfg.BotToken, PasswordMode: true},
					Label{Text: "Chat id:"},
					LineEdit{AssignTo: &chatLE, Text: cfg.ChatID},
					Label{Text: "Intervalo (hh:mm:ss):"},
					LineEdit{AssignTo: &ivalLE, Text: cfg.Intervalo},
				},
			},
			CheckBox{AssignTo: &autoCB, Text: "Cortar y enviar automáticamente cada intervalo", Checked: cfg.Auto},
			Composite{
				Layout: HBox{MarginsZero: true},
				Children: []Widget{
					PushButton{AssignTo: &btnStart, Text: "▶ Iniciar", OnClicked: onIniciar},
					PushButton{AssignTo: &btnCut, Text: "✂ Cortar y enviar", Enabled: false,
						OnClicked: func() {
							if eng != nil {
								eng.Cut()
							}
						}},
					PushButton{AssignTo: &btnFin, Text: "⏹ Finalizar", Enabled: false,
						OnClicked: func() {
							if eng == nil {
								return
							}
							if walk.MsgBox(mw, "¿Finalizar y procesar?",
								"Se corta la grabación, se sube lo que falte y la PC genera los PDFs. No se puede deshacer.",
								walk.MsgBoxYesNo|walk.MsgBoxIconQuestion) == walk.DlgCmdYes {
								eng.Finish()
							}
						}},
				},
			},
			Label{AssignTo: &statusLB, Text: "Estado: detenido"},
			Label{AssignTo: &partsLB, Text: ""},
			PushButton{Text: "Minimizar a la bandeja", OnClicked: func() { mw.Hide() }},
		},
	}).Create(); err != nil {
		panic(err)
	}

	// ---- bandeja del sistema ----
	ico := iconoApp()
	ni, err := walk.NewNotifyIcon(mw)
	if err != nil {
		panic(err)
	}
	defer ni.Dispose()
	if ico != nil {
		_ = ni.SetIcon(ico)
		mw.SetIcon(ico)
	}
	_ = ni.SetToolTip("Grabador de reuniones")
	_ = ni.SetVisible(true)

	restaurar := func() {
		mw.Show()
		win.ShowWindow(mw.Handle(), win.SW_RESTORE)
		win.SetForegroundWindow(mw.Handle())
	}
	ni.MouseDown().Attach(func(x, y int, button walk.MouseButton) {
		if button == walk.LeftButton {
			restaurar()
		}
	})

	addAccion := func(texto string, fn func()) {
		a := walk.NewAction()
		_ = a.SetText(texto)
		a.Triggered().Attach(fn)
		ni.ContextMenu().Actions().Add(a)
	}
	addAccion("Restaurar ventana", restaurar)
	addAccion("Cortar y enviar", func() {
		if eng != nil {
			eng.Cut()
		}
	})
	addAccion("Finalizar y procesar", func() {
		if eng != nil {
			eng.Finish()
		}
	})
	addAccion("Salir", func() {
		if eng != nil {
			eng.Kill() // cortar ffmpeg para no dejarlo grabando huérfano tras salir
		}
		walk.App().Exit(0)
	})

	// cerrar (X) = ocultar a la bandeja, salvo que no haya nada en curso
	mw.Closing().Attach(func(canceled *bool, reason walk.CloseReason) {
		*canceled = true
		mw.Hide()
		_ = ni.ShowInfo("Grabador", "Sigue corriendo en la bandeja. Clic para restaurar.")
	})

	// refresco periódico del estado
	go func() {
		for range time.Tick(time.Second) {
			mw.Synchronize(refrescar)
		}
	}()

	refrescar()
	mw.Run()
}

func fuenteLabels() []string {
	out := make([]string, len(fuentes))
	for i, f := range fuentes {
		out[i] = f.label
	}
	return out
}

func fmtDur(d time.Duration) string {
	s := int(d.Seconds())
	if s >= 3600 {
		return fmt.Sprintf("%d:%02d:%02d", s/3600, (s%3600)/60, s%60)
	}
	return fmt.Sprintf("%d:%02d", s/60, s%60)
}
