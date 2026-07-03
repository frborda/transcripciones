package main

import (
	"bytes"
	"encoding/json"
	"errors"
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

// tgError lleva el código HTTP de la respuesta de Telegram para poder distinguir
// un error permanente (token/chat/archivo) de uno transitorio (red, rate limit).
type tgError struct {
	code int
	msg  string
}

func (e *tgError) Error() string { return fmt.Sprintf("%d: %s", e.code, e.msg) }

// esPermanente: los 4xx (salvo 429 rate-limit) no se arreglan reintentando —
// 401 token inválido, 400 chat_id malo, 413 archivo demasiado grande. Reintentar
// eternamente solo bloquea la cola y el "fin" nunca sale.
func esPermanente(err error) bool {
	var te *tgError
	if errors.As(err, &te) {
		return te.code >= 400 && te.code < 500 && te.code != 429
	}
	return false
}

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
		return &tgError{resp.StatusCode, "sendMessage: " + strings.TrimSpace(string(b))}
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

	// Timeout total generoso: una parte tope (~30 MB) por un enlace lento tarda varios
	// minutos; 10 min cortaba subidas válidas a medio camino.
	cl := &http.Client{Timeout: 30 * time.Minute}
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
		return &tgError{resp.StatusCode, "sendDocument: " + strings.TrimSpace(string(b))}
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

// conReintento reintenta con backoff ante fallos transitorios (red, 5xx, 429) y
// devuelve true al lograrlo. Ante un error PERMANENTE (token/chat/tamaño) no insiste:
// deja el motivo real en el estado y devuelve false, para que la cola no quede
// bloqueada y el "fin" pueda salir igual.
func conReintento(desc string, fn func() error) bool {
	intento := 0
	for {
		err := fn()
		if err == nil {
			return true
		}
		if esPermanente(err) {
			estadoTxt = fmt.Sprintf("ERROR %s: %v — revisá token, chat id o tamaño del archivo", desc, err)
			return false
		}
		intento++
		estadoTxt = fmt.Sprintf("sin conexión, reintento %d (%s)", intento, desc)
		espera := time.Duration(intento) * 5 * time.Second
		if espera > 60*time.Second {
			espera = 60 * time.Second
		}
		time.Sleep(espera)
	}
}

var _ = bytes.MinRead
