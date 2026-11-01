import contextlib
import os
import sys
import threading
import time
import tkinter as tk
from tkinter import ttk, messagebox

import esptool
from serial.tools import list_ports

APP_VERSION = "1.0.2"
FW_VERSION = "1.5.2"
BIN_NAME = "IERIKHON-ESP32-v1.5.2-FULL.bin"


def resource_path(name: str) -> str:
    base = getattr(sys, "_MEIPASS", os.path.abspath(os.path.dirname(__file__)))
    return os.path.join(base, name)


class GuiWriter:
    def __init__(self, app):
        self.app = app
        self.buf = ""

    def write(self, text):
        if not text:
            return 0
        self.buf += str(text).replace("\r", "\n")
        while "\n" in self.buf:
            line, self.buf = self.buf.split("\n", 1)
            line = line.strip()
            if line:
                self.app.after(0, lambda t=line: self.app.write_log(t))
        return len(text)

    def flush(self):
        if self.buf.strip():
            line = self.buf.strip()
            self.buf = ""
            self.app.after(0, lambda t=line: self.app.write_log(t))


class App(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title(f"IERIKHON ESP32 Flasher v{APP_VERSION}")
        self.geometry("640x500")
        self.minsize(640, 500)
        self.configure(bg="#0b0e12")
        self.port_var = tk.StringVar()
        self.status_var = tk.StringVar(value="Подключи ESP32 по USB. Порт выберется автоматически.")
        self.busy = False
        self._build()
        self.after(250, self.refresh_ports)

    def _build(self):
        style = ttk.Style(self)
        try:
            style.theme_use("clam")
        except Exception:
            pass
        style.configure("TCombobox", fieldbackground="#171d23", background="#171d23", foreground="white", borderwidth=0)

        top = tk.Frame(self, bg="#0b0e12")
        top.pack(fill="x", padx=26, pady=(24, 8))
        tk.Label(top, text="IERIKHON", fg="white", bg="#0b0e12", font=("Segoe UI", 28, "bold")).pack(anchor="w")
        tk.Label(top, text=f"ESP32 FLASHER  •  firmware v{FW_VERSION}", fg="#62d5bd", bg="#0b0e12", font=("Segoe UI", 10, "bold")).pack(anchor="w", pady=(2, 0))

        card = tk.Frame(self, bg="#12171c", highlightbackground="#242e36", highlightthickness=1)
        card.pack(fill="x", padx=26, pady=14)

        tk.Label(card, text="ESP32 ПО USB", fg="#8ea0aa", bg="#12171c", font=("Segoe UI", 9, "bold")).pack(anchor="w", padx=18, pady=(16, 7))
        row = tk.Frame(card, bg="#12171c")
        row.pack(fill="x", padx=18, pady=(0, 16))
        self.combo = ttk.Combobox(row, textvariable=self.port_var, state="readonly", width=54)
        self.combo.pack(side="left", fill="x", expand=True)

        self.refresh_btn = tk.Button(row, text="ОБНОВИТЬ", command=self.refresh_ports, bg="#202a31", fg="white", activebackground="#2a363e", activeforeground="white", relief="flat", font=("Segoe UI", 9, "bold"), padx=14, pady=8, bd=0)
        self.refresh_btn.pack(side="left", padx=(10, 0))

        self.flash_btn = tk.Button(self, text="ПРОШИТЬ ESP32", command=self.start_flash, bg="#31545a", fg="white", activebackground="#3b666d", activeforeground="white", relief="flat", font=("Segoe UI", 19, "bold"), pady=17, bd=0, cursor="hand2")
        self.flash_btn.pack(fill="x", padx=26, pady=(4, 10))

        tk.Label(self, textvariable=self.status_var, fg="#dce5ea", bg="#0b0e12", font=("Segoe UI", 10), wraplength=580, justify="left").pack(fill="x", padx=26, pady=(0, 9))

        self.log = tk.Text(self, height=12, bg="#0f1418", fg="#b8c8d0", insertbackground="white", relief="flat", font=("Consolas", 9), state="disabled", wrap="word", bd=0, padx=10, pady=10)
        self.log.pack(fill="both", expand=True, padx=26, pady=(0, 20))
        self.write_log("Готово. Один клик: программа сама выберет USB-COM, переведёт ESP32 в загрузчик и перезагрузит после записи.")

    def write_log(self, text):
        self.log.configure(state="normal")
        self.log.insert("end", f"[{time.strftime('%H:%M:%S')}] {text}\n")
        self.log.see("end")
        self.log.configure(state="disabled")

    def refresh_ports(self):
        if self.busy:
            return
        ports = list(list_ports.comports())
        values = []
        preferred = None
        for p in ports:
            label = f"{p.device} — {p.description}"
            values.append(label)
            desc = ((p.description or "") + " " + (p.hwid or "")).lower()
            if preferred is None and any(x in desc for x in ["cp210", "silicon labs", "ch340", "ch341", "usb serial", "uart", "wch"]):
                preferred = label

        self.combo["values"] = values
        if preferred:
            self.port_var.set(preferred)
        elif len(values) == 1:
            self.port_var.set(values[0])
        elif values and self.port_var.get() not in values:
            self.port_var.set(values[0])
        elif not values:
            self.port_var.set("")

        if self.port_var.get():
            port = self.port_var.get().split(" — ", 1)[0]
            self.status_var.set(f"Найдена ESP32/USB: {port}. Нажми «ПРОШИТЬ ESP32». Больше ничего зажимать не нужно.")
        else:
            self.status_var.set("COM-порт не найден. Проверь USB data-кабель и драйвер CP210x/CH340.")
        self.write_log(f"Найдено COM-портов: {len(values)}")

    def start_flash(self):
        if self.busy:
            return
        if not self.port_var.get().strip():
            self.refresh_ports()
        selected = self.port_var.get().strip()
        if not selected:
            messagebox.showerror("IERIKHON", "ESP32 не найдена. Подключи её USB data-кабелем.")
            return

        port = selected.split(" — ", 1)[0].strip()
        bin_path = resource_path(BIN_NAME)
        if not os.path.isfile(bin_path):
            messagebox.showerror("IERIKHON", f"Встроенная прошивка не найдена: {BIN_NAME}")
            return

        self.busy = True
        self.flash_btn.configure(state="disabled", text="ПРОШИВАЮ…")
        self.refresh_btn.configure(state="disabled")
        self.combo.configure(state="disabled")
        self.status_var.set("Автовход в загрузчик → запись → автоперезагрузка. Не отключай USB.")
        self.write_log(f"Старт: port={port}, firmware={FW_VERSION}")
        threading.Thread(target=self._flash_worker, args=(port, bin_path), daemon=True).start()

    def _run_esptool(self, port, bin_path, baud):
        args = [
            "--chip", "esp32",
            "--port", port,
            "--baud", str(baud),
            "--before", "default_reset",
            "--after", "hard_reset",
            "--connect-attempts", "10",
            "write_flash", "0x0", bin_path,
        ]
        writer = GuiWriter(self)
        with contextlib.redirect_stdout(writer), contextlib.redirect_stderr(writer):
            esptool.main(args)
        writer.flush()

    def _flash_worker(self, port, bin_path):
        ok = False
        errors = []
        for baud in (460800, 115200):
            try:
                self.after(0, lambda b=baud: self.write_log(f"Автоподключение к bootloader, baud={b}"))
                self._run_esptool(port, bin_path, baud)
                ok = True
                break
            except SystemExit as e:
                if getattr(e, "code", 1) in (0, None):
                    ok = True
                    break
                errors.append(f"baud {baud}: esptool code {e.code}")
            except Exception as e:
                errors.append(f"baud {baud}: {type(e).__name__}: {e}")
            time.sleep(0.7)

        self.after(0, lambda: self._flash_done(ok, " | ".join(errors)))

    def _flash_done(self, ok, error_text):
        self.busy = False
        self.flash_btn.configure(state="normal", text="ПРОШИТЬ ESP32")
        self.refresh_btn.configure(state="normal")
        self.combo.configure(state="readonly")

        if ok:
            self.status_var.set("ГОТОВО. ESP32 прошита IERIKHON v1.5.2 и автоматически перезагружена.")
            self.write_log("SUCCESS: прошивка завершена.")
            messagebox.showinfo("IERIKHON", "ГОТОВО.\n\nESP32 прошита IERIKHON v1.5.2.\nBOOT/EN нажимать не нужно.")
        else:
            self.status_var.set("Автопрошивка не завершилась. Смотри последнюю строку лога.")
            self.write_log("ERROR: " + (error_text or "не удалось подключиться к загрузчику"))
            messagebox.showerror("IERIKHON", "Не удалось автоматически прошить ESP32.\n\n" + (error_text or "Проверь USB data-кабель / COM / драйвер."))


if __name__ == "__main__":
    App().mainloop()
