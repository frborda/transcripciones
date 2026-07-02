package main

import (
	"os/exec"
	"syscall"
)

// ocultarConsola evita que los procesos hijo (ffmpeg) muestren una ventana de
// consola al lanzarse desde la app GUI.
func ocultarConsola(cmd *exec.Cmd) {
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x08000000} // CREATE_NO_WINDOW
}
