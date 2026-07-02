package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

// Config del grabador (grabador.config.json junto al .exe).
type Config struct {
	BotToken  string `json:"bot_token"`
	ChatID    string `json:"chat_id"`
	Intervalo string `json:"intervalo"` // "hh:mm:ss", "mm:ss" o "ss"
	Auto      bool   `json:"auto"`
	Fuente    string `json:"fuente"` // mic | desktop | ambos
	Device    string `json:"device"` // micrófono elegido (alternative name dshow)
}

const (
	configName = "grabador.config.json"
	stateName  = "grabador.state.json"
	grabDir    = "grabaciones"
)

func defaultConfig() Config {
	return Config{
		BotToken:  "",
		ChatID:    "-5418589182", // grupo "Trasncripciones" por defecto
		Intervalo: "00:01:00",
		Auto:      true,
		Fuente:    FuenteMic,
		Device:    "",
	}
}

// dirBase: carpeta donde está el ejecutable (config/estado/grabaciones cuelgan de ahí).
func dirBase() string {
	exe, err := os.Executable()
	if err != nil {
		wd, _ := os.Getwd()
		return wd
	}
	return filepath.Dir(exe)
}

func configPath() string { return filepath.Join(dirBase(), configName) }

func cargarConfig() (Config, error) {
	cfg := defaultConfig()
	b, err := os.ReadFile(configPath())
	if err != nil {
		if os.IsNotExist(err) {
			_ = guardarConfig(cfg) // deja una plantilla para completar
			return cfg, errNoConfig
		}
		return cfg, err
	}
	if err := json.Unmarshal(b, &cfg); err != nil {
		return cfg, err
	}
	if cfg.ChatID == "" {
		cfg.ChatID = defaultConfig().ChatID
	}
	if cfg.Intervalo == "" {
		cfg.Intervalo = "00:01:00"
	}
	if cfg.Fuente == "" {
		cfg.Fuente = FuenteMic
	}
	return cfg, nil
}

func guardarConfig(cfg Config) error {
	b, _ := json.MarshalIndent(cfg, "", "  ")
	return os.WriteFile(configPath(), b, 0644)
}

// parseHMS: "hh:mm:ss" | "mm:ss" | "ss" -> segundos (mínimo 5).
func parseHMS(s string) int {
	p := strings.Split(strings.TrimSpace(s), ":")
	n := func(x string) int { v, _ := strconv.Atoi(strings.TrimSpace(x)); return v }
	var seg int
	switch len(p) {
	case 1:
		seg = n(p[0])
	case 2:
		seg = n(p[0])*60 + n(p[1])
	case 3:
		seg = n(p[0])*3600 + n(p[1])*60 + n(p[2])
	}
	if seg < 5 {
		seg = 5
	}
	return seg
}

func fmtHMS(seg int) string {
	h, m, s := seg/3600, (seg%3600)/60, seg%60
	return pad2(h) + ":" + pad2(m) + ":" + pad2(s)
}

func pad2(n int) string {
	if n < 10 {
		return "0" + strconv.Itoa(n)
	}
	return strconv.Itoa(n)
}

// Estado de la sesión en curso (para retomar tras un cierre/caída).
type Estado struct {
	Sesion string `json:"sesion"`
	Parte  int    `json:"parte"`
	Activa bool   `json:"activa"`
}

func statePath() string { return filepath.Join(dirBase(), stateName) }

func cargarEstado() Estado {
	var e Estado
	b, err := os.ReadFile(statePath())
	if err == nil {
		_ = json.Unmarshal(b, &e)
	}
	return e
}

func guardarEstado(e Estado) {
	b, _ := json.MarshalIndent(e, "", "  ")
	_ = os.WriteFile(statePath(), b, 0644)
}
