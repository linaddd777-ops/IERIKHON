#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <esp_bt.h>

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

static void publish(const String& text) {
  Serial.print("[BLE TX] ");
  Serial.println(text);

  if (!commandCharacteristic) return;
  commandCharacteristic->setValue(text.c_str());

  if (clientConnected) {
    commandCharacteristic->notify();
  }
}

static void resetSafetyState(bool notifyClient) {
  armed = false;
  armedUntil = 0;
  relaySafeOff();
  if (notifyClient) publish("DISARMED");
}

static void configureAndStartAdvertising() {
  BLEAdvertising* advertising = BLEDevice::getAdvertising();
  advertising->stop();

  BLEAdvertisementData adv;
  adv.setFlags(0x06);
  adv.setCompleteServices(BLEUUID(SERVICE_UUID));
  advertising->setAdvertisementData(adv);

  BLEAdvertisementData scanResponse;
  scanResponse.setName(DEVICE_NAME);
  advertising->setScanResponseData(scanResponse);
  advertising->setScanResponse(true);

  advertising->setMinPreferred(0x06);
  advertising->setMaxPreferred(0x12);
  advertising->start();

  Serial.println("[BLE] advertising IERIKHON-01 + service UUID");
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
    delay(250);
    configureAndStartAdvertising();
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
      publish("PONG:IERIKHON:1.4.0");
      return;
    }

    if (cmd == "STATUS") {
      uint32_t now = millis();
      if (armed && deadlineReached(now, armedUntil)) armed = false;

      if (armed) {
        uint32_t leftMs = armedUntil - now;
        publish("STATUS:ARMED:" + String((leftMs + 999) / 1000));
      } else {
        publish("STATUS:SAFE");
      }
      return;
    }

    if (cmd == "ARM") {
      armed = true;
      armedUntil = millis() + ARM_WINDOW_MS;
      publish("ARMED");
      return;
    }

    if (cmd == "START") {
      uint32_t now = millis();

      if (!armed) {
        publish("DENIED:NOT_ARMED");
        return;
      }

      if (deadlineReached(now, armedUntil)) {
        armed = false;
        publish("DENIED:ARM_TIMEOUT");
        return;
      }

      armed = false;
      digitalWrite(RELAY_PIN, RELAY_ON);
      relayActive = true;
      relayOffAt = now + RELAY_PULSE_MS;
      publish("START_COMMAND_OK");
      return;
    }

    if (cmd == "DISARM" || cmd == "STOP") {
      resetSafetyState(true);
      return;
    }

    publish("DENIED:UNKNOWN_COMMAND");
  }
};

void setup() {
  pinMode(RELAY_PIN, OUTPUT);
  relaySafeOff();

  Serial.begin(115200);
  delay(150);
  Serial.println();
  Serial.println("IERIKHON firmware v1.4.0");
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
  commandCharacteristic->setValue("STATUS:SAFE");

  service->start();
  configureAndStartAdvertising();

  Serial.println("[BOOT] READY");
}

void loop() {
  uint32_t now = millis();

  if (armed && deadlineReached(now, armedUntil)) {
    armed = false;
    publish("ARM_TIMEOUT");
  }

  if (relayActive && deadlineReached(now, relayOffAt)) {
    relaySafeOff();
    publish("RELAY_OFF");
  }

  delay(5);
}
