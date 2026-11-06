package me.kavishdevar.librepods.shizuku;

/** Selected dongle feature reports only; the kernel retains consumer/media-key input. */
interface IUsbHidConnection {
    byte[] descriptor();
    byte[] exchange(int operation, int report, in byte[] payload);
    oneway void close();
}
