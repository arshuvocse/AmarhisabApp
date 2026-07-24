# Amarhisab — Android app

Kotlin WebView wrapper for **amarhisab.com** with native Bluetooth thermal
printer support, built per this architecture:

```
Splash screen -> Android WebView -> amarhisab.com
                                        |
                                  JavaScript bridge
                                        |
                                 Native Kotlin API
                              /          |          \
                Bluetooth manager  Printer settings  Printer
                        \                              /
                     ESC/POS engine            Bitmap renderer
                              \                  /
                          Bluetooth thermal printer
```

## Project structure

```
app/src/main/java/com/amarhisab/app/
├── SplashActivity.kt              Splash screen -> launches MainActivity
├── MainActivity.kt                Hosts the WebView, loads amarhisab.com,
│                                   registers the JS bridge, handles permissions
├── WebAppInterface.kt             The JavaScript bridge (window.AndroidBridge)
└── printer/
    ├── BluetoothPrinterManager.kt Native Kotlin API: connects to the saved
    │                               printer, drives print jobs (this is also
    │                               the "Bluetooth manager" node)
    ├── PrinterSettingsActivity.kt "Printer settings" screen — pick & save
    │                               a paired device as default
    ├── DeviceListAdapter.kt       RecyclerView adapter for paired devices
    ├── EscPosEncoder.kt           "ESC/POS engine" — builds raw printer
    │                               byte commands (text, formatting, raster)
    └── BitmapPrintRenderer.kt     "Bitmap renderer" — decodes/scales images
                                    sent from the web page before printing
```

## JavaScript bridge API (for the amarhisab.com frontend)

Once the WebView loads the site, these are available on `window.AndroidBridge`:

```js
// Check connection state & saved device info
window.AndroidBridge.isPrinterConnected(); // -> boolean
window.AndroidBridge.hasSavedPrinter();     // -> boolean
window.AndroidBridge.getSavedPrinterName();  // -> string (e.g. "RP410 Thermal Printer")
window.AndroidBridge.getSavedPrinterAddress(); // -> string (e.g. "00:11:22:33:44:55")

// Disconnect current printer connection
window.AndroidBridge.disconnectPrinter();

// Open native printer picker/settings screen
window.AndroidBridge.openPrinterSettings();

// Print a structured receipt (recommended)
window.AndroidBridge.printReceipt(JSON.stringify({
  shopName: "Amarhisab Store",
  items: [
    { name: "Item A", qty: 2, price: 150.0 },
    { name: "Item B", qty: 1, price: 50.0 }
  ],
  total: 350.0,
  footer: "Dhonnobad, abar asben!"
}));

// Print plain text
window.AndroidBridge.printText("Hello from amarhisab.com");

// Print an image (e.g. logo or QR) as a base64 PNG/JPEG
window.AndroidBridge.printBitmapBase64(base64String);
```

> Guard every call with a feature check, since these methods only exist
> inside the Android app's WebView, not in a regular mobile/desktop browser:
> ```js
> if (window.AndroidBridge) {
>   window.AndroidBridge.printReceipt(JSON.stringify(receipt));
> } else {
>   // fall back to normal browser print or show a message
> }
> ```

## Setup

1. Open the project root in Android Studio (Hedgehog+).
2. Let Gradle sync — it will pull AndroidX, Material, and RecyclerView deps.
3. Confirm `app/src/main/res/values/strings.xml` → `site_url` points to your
   environment (production is already set to `https://www.amarhisab.com`).
4. Run on a device with a paired Bluetooth thermal printer to test printing —
   the emulator has no real Bluetooth stack.
5. First run: grant Bluetooth permission when prompted, then go to
   **Printer settings** to pair and save a default printer.

## Notes / next steps

- `BluetoothPrinterManager` uses the standard SPP UUID, compatible with most
  generic 58mm/80mm Bluetooth thermal receipt printers.
- Pairing itself still happens via Android's system Bluetooth settings; this
  app only lists **already-paired** devices to connect to.
- No app icon/launcher assets are included — drop your own into
  `res/mipmap-*/ic_launcher.png` before building a release APK.
- `usesCleartextTraffic="false"` in the manifest assumes the site is served
  over HTTPS — it is, given the `https://www.amarhisab.com` URL.
