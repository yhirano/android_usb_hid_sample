#include "mbed.h"
#include "USBHID.h"

// USB HIDデバイス
USBHID hid;

// HID読み込み
HID_REPORT hidReceive;
// HID書き込み
HID_REPORT hidSend;

// LED
DigitalOut led1(LED1);
DigitalOut led2(LED2);
DigitalOut led3(LED3);
DigitalOut led4(LED4);

int main(void) {
  // 起動時にはLEDは消灯する
  led1 = 0;
  led2 = 0;
  led3 = 0;
  led4 = 0;

  while (true) {
    // USBからデータを読み込む
    bool readResult = hid.read(&hidReceive);

    if (readResult) {
      // USBからのデータが4バイト以上の場合...
      if (hidReceive.length >= 4) {
        // USBからのデータを解析する
        //
        // 4バイトのデータで、LEDを点灯するかどうかのデータが各1バイトずつに格納されている
        // | LED1 | LED2 | LED3 | LED4 |
        led1 = hidReceive.data[0] == 0 ? 0 : 1;
        led2 = hidReceive.data[1] == 0 ? 0 : 1;
        led3 = hidReceive.data[2] == 0 ? 0 : 1;
        led4 = hidReceive.data[3] == 0 ? 0 : 1;

        // USBで0(成功)を返す
        hidSend.length = 1;
        hidSend.data[0] = 0;
        hid.sendNB(&hidSend);
      }
      // USBからのデータが4バイト以外の場合...
      else if (hidReceive.length > 0) {
        // USBで1(エラー)を返す
        hidSend.length = 1;
        hidSend.data[0] = 1;
        hid.sendNB(&hidSend);
      }
    }
  }
}