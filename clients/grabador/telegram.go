package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"mime/multipart"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// Cliente mínimo de la Bot API de Telegram (solo stdlib).

func tgURL(token, metodo string) string {
	return "https://api.telegram.org/bot" + token + "/" + metodo
}

func sendMessage(token, chatID, texto string) error {
	form := url.Values{}
	form.Set("chat_id", chatID)
	form.Set("text", texto)
	cl := &http.Client{Timeout: 30 * time.Second}
	resp, err := cl.PostForm(tgURL(token, "sendMessage"), form)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		b, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("sendMessage %d: %s", resp.StatusCode, strings.TrimSpace(string(b)))
	}
	return nil
}

func sendDocument(token, chatID, ruta string) error {
	f, err := os.Open(ruta)
	if err != nil {
		return err
	}
	defer f.Close()

	pr, pw := io.Pipe()
	mw := multipart.NewWriter(pw)
	go func() {
		var e error
		defer func() { _ = pw.CloseWithError(e) }()
		if e = mw.WriteField("chat_id", chatID); e != nil {
			return
		}
		part, err := mw.CreateFormFile("document", filepath.Base(ruta))
		if err != nil {
			e = err
			return
		}
		if _, e = io.Copy(part, f); e != nil {
			return
		}
		e = mw.Close()
	}()

	cl := &http.Client{Timeout: 10 * time.Minute}
	req, err := http.NewRequest("POST", tgURL(token, "sendDocument"), pr)
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", mw.FormDataContentType())
	resp, err := cl.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		b, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("sendDocument %d: %s", resp.StatusCode, strings.TrimSpace(string(b)))
	}
	return nil
}

// getMe valida el token y devuelve el nombre del bot.
func getMe(token string) (string, error) {
	cl := &http.Client{Timeout: 15 * time.Second}
	resp, err := cl.Get(tgURL(token, "getMe"))
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	var r struct {
		OK     bool `json:"ok"`
		Result struct {
			Username string `json:"username"`
		} `json:"result"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&r); err != nil {
		return "", err
	}
	if !r.OK {
		return "", fmt.Errorf("token inválido")
	}
	return r.Result.Username, nil
}

// reintentos con backoff: nunca devuelve hasta lograrlo (mantiene la secuencia).
func conReintento(desc string, fn func() error) {
	intento := 0
	for {
		if err := fn(); err == nil {
			return
		} else {
			intento++
			estadoTxt = fmt.Sprintf("sin conexión, reintento %d (%s)", intento, desc)
			espera := time.Duration(intento) * 5 * time.Second
			if espera > 60*time.Second {
				espera = 60 * time.Second
			}
			time.Sleep(espera)
		}
	}
}

var _ = bytes.MinRead
