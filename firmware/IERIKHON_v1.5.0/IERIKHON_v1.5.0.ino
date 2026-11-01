#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

static const char* FW_VERSION = "1.5.0";
static const char* DEVICE_NAME = "IERIKHON-01";
static const char* SERVICE_UUID = "6f4f0001-8f3a-4d7a-9c12-4a494552494b";
static const char* COMMAND_UUID = "6f4f0002-8f3a-4d7a-9c12-4a494552494b";

static const uint8_t RELAY_PIN = 25;
static const uint8_t RELAY_ON = LOW;
static const uint8_t RELAY_OFF = HIGH;

static const uint32_t ARM_WINDOW_MS = 15000;
static const uint32_t RELAY_PULSE_MS = 800;

BLEServer* bleServer = nullptr;
BLECharacteristic* commandCharacteristic = nullptr;

volatile bool clientConnected = false;
bool armed = false;
bool relayActive = false;
uint32_t armedUntil = 0;
uint32_t relayOffAt = 0;

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
  BLEAdvertising* advertising = BLEDevice::getAdvertising();
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

  Serial.println("[BLE] advertising IERIKHON-01");
}

class ServerCallbacks final : public BLEServerCallbacks {
  void onConnect(BLEServer* server) override {
    (void)server;
    clientConnected = true;
    Serial.println("[BLE] client connected");
  }

  void onDisconnect(BLEServer* server) override {
    (void)server;
    clientConnected = false;
    resetSafetyState(false);
    Serial.println("[BLE] client disconnected -> SAFE");
    delay(100);
    startAdvertising();
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

    if (cmd == "STATUS") {
      uint32_t now = millis();
      if (armed && deadlineReached(now, armedUntil)) armed = false;

      if (armed) {
        uint32_t leftMs = armedUntil - now;
        reply("STATUS:ARMED:" + String((leftMs + 999) / 1000));
      } else {
        reply("STATUS:SAFE");
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
      relayOffAt = now + RELAY_PULSE_MS;
      reply("START_COMMAND_OK:GPIO25:800");
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
  Serial.println();
  Serial.println("IERIKHON firmware v1.5.0");
  Serial.println("Relay: GPIO25, active LOW, pulse 800 ms");

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
  commandCharacteristic->setValue("BOOT:IERIKHON:1.5.0:GPIO25");

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
    reply("RELAY_OFF:GPIO25");
  }

  delay(5);
}
