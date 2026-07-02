package main

import "errors"

// estado global mostrado en la UI (lo actualiza el engine y lo lee la GUI)
var estadoTxt = "detenido"

var errNoConfig = errors.New("no config")
