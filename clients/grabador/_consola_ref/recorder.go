package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"
)

// detectarDispositivo devuelve el "alternative name" del primer micrófono dshow
// (ASCII estable, evita problemas con acentos del nombre amigable).
func detectarDispositivo() (string, string) {
	cmd := exec.Command("ffmpeg", "-hide_banner", "-list_devices", "true", "-f", "dshow", "-i", "dummy")
	out, _ := cmd.CombinedOutput()
	lineas := strings.Split(string(out), "\n")
	reNombre := regexp.MustCompile(`"([^"]+)"\s*\(audio\)`)
	reAlt := regexp.MustCompile(`Alternative name\s*"([^"]+)"`)
	for i, ln := range lineas {
		if m := reNombre.FindStringSubmatch(ln); m != nil {
			nombre := m[1]
			alt := nombre
			if i+1 < len(lineas) {
				if a := reAlt.FindStringSubmatch(lineas[i+1]); a != nil {
					alt = a[1]
				}
			}
			return nombre, alt
		}
	}
	return "", ""
}

func dirGrab() string {
	d := filepath.Join(dirBase(), grabDir)
	_ = os.MkdirAll(d, 0755)
	return d
}

func segPath(sesion string, parte int) string {
	return filepath.Join(dirGrab(), fmt.Sprintf("reunion_%s_p%03d.m4a", sesion, parte))
}

// ffmpegSegmento arranca la captura de UN segmento a 'ruta'. Se detiene solo al
// llegar a maxSeg, o antes si se le escribe 'q' al stdin (corte manual/auto).
func ffmpegSegmento(device, ruta string, maxSeg int) (*exec.Cmd, error) {
	args := []string{
		"-hide_banner", "-loglevel", "error", "-y",
		"-f", "dshow", "-i", "audio=" + device,
		"-ac", "1", "-ar", "44100", "-c:a", "aac", "-b:a", "96k",
		"-t", fmt.Sprintf("%d", maxSeg),
		ruta,
	}
	cmd := exec.Command("ffmpeg", args...)
	stdin, err := cmd.StdinPipe()
	if err != nil {
		return nil, err
	}
	if err := cmd.Start(); err != nil {
		return nil, err
	}
	pipes[cmd] = stdin // guardar el stdin para poder mandar 'q'
	return cmd, nil
}

var pipes = map[*exec.Cmd]interface{ Write([]byte) (int, error) }{}

// detener ffmpeg con 'q' (finaliza el contenedor mp4 correctamente).
func detenerFfmpeg(cmd *exec.Cmd) {
	if p, ok := pipes[cmd]; ok && p != nil {
		_, _ = p.Write([]byte("q\n"))
		delete(pipes, cmd)
	}
}

func archivoOK(ruta string) bool {
	fi, err := os.Stat(ruta)
	return err == nil && fi.Size() > 0
}
