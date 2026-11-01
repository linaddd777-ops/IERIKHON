#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <Preferences.h>

static const char* FW_VERSION = "1.5.3";
static const char* DEVICE_NAME = "IERIKHON-01";
static const char* SERVICE_UUID = "6f4f0001-8f3a-4d7a-9c12-4a494552494b";
static const char* COMMAND_UUID = "6f4f0002-8f3a-4d7a-9c12-4a494552494b";

static const uint8_t RELAY_PIN = 25;
static const uint8_t RELAY_ON = LOW;
static const uint8_t RELAY_OFF = HIGH;

static const uint32_t ARM_WINDOW_MS = 15000;
static const uint32_t DEFAULT_RELAY_PULSE_MS = 800;
static const uint32_t MIN_RELAY_PULSE_MS = 500;
static const uint32_t MAX_RELAY_PULSE_MS = 3000;
static const uint32_t ADV_RESTART_DELAY_MS = 250;
static const uint32_t ADV_HEALTH_MS = 8000;

BLEServer* bleServer = nullptr;
BLECharacteristic* commandCharacteristic = nullptr;
Preferences prefs;

volatile bool clientConnected = false;
volatile bool advertisingRestartRequested = false;
volatile uint32_t advertisingRestartAt = 0;
bool armed = false;
bool relayActive = false;
uint32_t armedUntil = 0;
uint32_t relayOffAt = 0;
uint32_t relayPulseMs = DEFAULT_RELAY_PULSE_MS;
uint32_t nextAdvertisingHealthAt = 0;

static bool deadlineReached(uint32_t now, uint32_t deadline) {
  return (int32_t)(now - deadline) >= 0;
}

static void relaySafeOff() {
  digitalWrite(RELAY_PIN, RELAY_OFF);
  relayActive = false;
}

static void reply(const String& text) {
  Serial.print("[BLE TX] ");
  Serial.println(text);
  if (!commandCharacteristic) return;
  commandCharacteristic->setValue(text.c_str());
  if (clientConnected) commandCharacteristic->notify();
}

static void resetSafetyState(bool notifyClient) {
  armed = false;
  armedUntil = 0;
  relaySafeOff();
  if (notifyClient) reply("DISARMED");
}

static void startAdvertising() {
  if (clientConnected) return;

  BLEAdvertising* advertising = BLEDevice::getAdvertising();
  if (!advertising) return;

  advertising->stop();

  BLEAdvertisementData adv;
  adv.setFlags(0x06);
  adv.setCompleteServices(BLEUUID(SERVICE_UUID));
  advertising->setAdvertisementData(adv);

  BLEAdvertisementData scanResponse;
  scanResponse.setName(DEVICE_NAME);
  advertising->setScanResponseData(scanResponse);

  advertising->setMinPreferred(0x06);
  advertising->setMaxPreferred(0x12);
  advertising->start();

  nextAdvertisingHealthAt = millis() + ADV_HEALTH_MS;
  Serial.println("[BLE] advertising IERIKHON-01");
}

static void scheduleAdvertisingRestart(uint32_t delayMs = ADV_RESTART_DELAY_MS) {
  advertisingRestartAt = millis() + delayMs;
  advertisingRestartRequested = true;
}

static bool parsePulseCommand(const String& cmd, uint32_t& value) {
  const String prefix = "SET_PULSE:";
  if (!cmd.startsWith(prefix)) return false;

  String tail = cmd.substring(prefix.length());
  tail.trim();
  if (tail.length() == 0) return false;

  for (size_t i = 0; i < tail.length(); i++) {
    if (!isDigit(tail[i])) return false;
  }

  value = tail.toInt();
  return true;
}

class ServerCallbacks final : public BLEServerCallbacks {
  void onConnect(BLEServer* server) override {
    (void)server;
    clientConnected = true;
    advertisingRestartRequested = false;
    Serial.println("[BLE] client connected");
  }

  void onDisconnect(BLEServer* server) override {
    (void)server;
    clientConnected = false;
    resetSafetyState(false);
    Serial.println("[BLE] client disconnected -> SAFE; advertising restart scheduled");
    scheduleAdvertisingRestart();
  }
};

class CommandCallbacks final : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic* characteristic) override {
    String cmd(characteristic->getValue().c_str());
    cmd.trim();
    cmd.toUpperCase();

    Serial.print("[BLE RX] ");
    Serial.println(cmd);

    if (cmd == "PING") {
      reply(String("PONG:IERIKHON:") + FW_VERSION);
      return;
    }

    if (cmd == "GET_PULSE") {
      reply("PULSE:" + String(relayPulseMs));
      return;
    }

    uint32_t requestedPulse = 0;
    if (cmd.startsWith("SET_PULSE:")) {
      if (!parsePulseCommand(cmd, requestedPulse)) {
        reply("DENIED:BAD_PULSE");
        return;
      }

      if (relayActive) {
        reply("DENIED:RELAY_BUSY");
        return;
      }

      if (requestedPulse < MIN_RELAY_PULSE_MS || requestedPulse > MAX_RELAY_PULSE_MS) {
        reply("DENIED:PULSE_RANGE");
        return;
      }

      relayPulseMs = requestedPulse;
      prefs.putUInt("pulse_ms", relayPulseMs);
      reply("PULSE_OK:" + String(relayPulseMs));
      return;
    }

    if (cmd == "STATUS") {
      uint32_t now = millis();
      if (armed && deadlineReached(now, armedUntil)) armed = false;

      if (armed) {
        uint32_t leftMs = armedUntil - now;
        reply("STATUS:ARMED:" + String((leftMs + 999) / 1000) + ":PULSE:" + String(relayPulseMs));
      } else {
        reply("STATUS:SAFE:PULSE:" + String(relayPulseMs));
      }
      return;
    }

    if (cmd == "ARM") {
      if (relayActive) {
        reply("DENIED:RELAY_BUSY");
        return;
      }

      armed = true;
      armedUntil = millis() + ARM_WINDOW_MS;
      reply("ARMED");
      return;
    }

    if (cmd == "START") {
      uint32_t now = millis();

      if (relayActive) {
        reply("DENIED:RELAY_BUSY");
        return;
      }

      if (!armed) {
        reply("DENIED:NOT_ARMED");
        return;
      }

      if (deadlineReached(now, armedUntil)) {
        armed = false;
        reply("DENIED:ARM_TIMEOUT");
        return;
      }

      armed = false;
      digitalWrite(RELAY_PIN, RELAY_ON);
      relayActive = true;
      relayOffAt = now + relayPulseMs;
      reply("START_COMMAND_OK:GPIO25:" + String(relayPulseMs));
      return;
    }

    if (cmd == "DISARM" || cmd == "STOP") {
      resetSafetyState(true);
      return;
    }

    reply("DENIED:UNKNOWN_COMMAND");
  }
};

void setup() {
  digitalWrite(RELAY_PIN, RELAY_OFF);
  pinMode(RELAY_PIN, OUTPUT);
  relaySafeOff();

  Serial.begin(115200);
  delay(150);

  prefs.begin("ierikhon", false);
  relayPulseMs = prefs.getUInt("pulse_ms", DEFAULT_RELAY_PULSE_MS);
  if (relayPulseMs < MIN_RELAY_PULSE_MS || relayPulseMs > MAX_RELAY_PULSE_MS) {
    relayPulseMs = DEFAULT_RELAY_PULSE_MS;
    prefs.putUInt("pulse_ms", relayPulseMs);
  }

  Serial.println();
  Serial.println("IERIKHON firmware v1.5.3");
  Serial.print("Relay: GPIO25, active LOW, pulse ");
  Serial.print(relayPulseMs);
  Serial.println(" ms");

  BLEDevice::init(DEVICE_NAME);
  BLEDevice::setMTU(64);
  BLEDevice::setPower(ESP_PWR_LVL_P9);

  bleServer = BLEDevice::createServer();
  bleServer->setCallbacks(new ServerCallbacks());

  BLEService* service = bleServer->createService(SERVICE_UUID);
  commandCharacteristic = service->createCharacteristic(
    COMMAND_UUID,
    BLECharacteristic::PROPERTY_READ |
    BLECharacteristic::PROPERTY_WRITE |
    BLECharacteristic::PROPERTY_WRITE_NR |
    BLECharacteristic::PROPERTY_NOTIFY
  );

  commandCharacteristic->setCallbacks(new CommandCallbacks());
  commandCharacteristic->addDescriptor(new BLE2902());
  commandCharacteristic->setValue(
    (String("BOOT:IERIKHON:") + FW_VERSION + ":GPIO25:PULSE:" + String(relayPulseMs)).c_str()
  );

  service->start();
  startAdvertising();

  Serial.println("[BOOT] READY");
}

void loop() {
  uint32_t now = millis();

  if (armed && deadlineReached(now, armedUntil)) {
    armed = false;
    reply("ARM_TIMEOUT");
  }

  if (relayActive && deadlineReached(now, relayOffAt)) {
    relaySafeOff();
    reply("RELAY_OFF:GPIO25:" + String(relayPulseMs));
  }

  if (!clientConnected && advertisingRestartRequested && deadlineReached(now, advertisingRestartAt)) {
    advertisingRestartRequested = false;
    startAdvertising();
  }

  if (!clientConnected && !advertisingRestartRequested && deadlineReached(now, nextAdvertisingHealthAt)) {
    Serial.println("[BLE] advertising health restart");
    startAdvertising();
  }

  delay(5);
}
