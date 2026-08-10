# Merek — NetOps Console

<img src="../web/public/logo-netops.svg" alt="NetOps Console" height="44">

Nama tampilan produk adalah **NetOps Console**. `ftth` adalah codename internal
(nama repo, package, module) dan **tidak pernah tampil ke pengguna**.

---

## Lambang

Satu **feeder** (cincin) memecah lewat **splitter** menjadi tiga **drop** (simpul
padat) — topologi PON yang persis dikelola aplikasi ini.

```
   ◎────────┬──────●
            ├──────●
            ╰──────●
 feeder  splitter  drop
```

Tiga keputusan bentuk yang jangan diubah tanpa sengaja:

1. **Tikungannya membulat, bukan siku.** Serat optik punya radius tekuk minimum;
   lambang yang menekuk 90° menggambarkan sesuatu yang di lapangan berarti serat
   patah.
2. **Sumber = cincin, ujung = simpul padat.** Hierarkinya dibaca dari bentuk, bukan
   warna — jadi tetap terbaca saat dicetak hitam-putih atau di tile satu warna.
3. **Grid 32 dengan stroke 2.4.** Ditera pada favicon 16px: di bawah ketebalan itu
   ketiga cabangnya melebur jadi satu gumpalan.

## Warna

| Peran | Nilai |
|---|---|
| Aksen merek (Azure Blue) | `#0078D4` |
| Gradasi tile (135°) | `#2899F5` → `#0078D4` |
| Tinta wordmark | `#1B1A19` |
| Tinta pendamping ("Console") | `#605E5C` |

Warna status (`--good`, `--warning`, `--serious`, `--critical`) **tidak pernah**
dipakai di lambang. Di design system, warna status selalu berarti keadaan sistem —
memakainya sebagai hiasan merek melunturkan arti itu.

## Berkas

| Berkas | Untuk apa |
|---|---|
| [`web/public/favicon.svg`](../web/public/favicon.svg) | Tile biru + glyph putih. Favicon & sumber kanonis geometri. |
| [`web/public/logo-mark.svg`](../web/public/logo-mark.svg) | Glyph saja (biru, latar transparan) — cetak, tanda air, latar terang. |
| [`web/public/logo-netops.svg`](../web/public/logo-netops.svg) | Kunci merek horizontal: tile + wordmark. README, dokumen, email. |
| [`web/public/apple-touch-icon.png`](../web/public/apple-touch-icon.png) | Ikon layar-utama iOS, 180×180, penuh-bidang. |
| [`web/src/components/atoms/BrandMark.tsx`](../web/src/components/atoms/BrandMark.tsx) | Glyph sebagai komponen React ber-`currentColor`, dipakai di dalam aplikasi. |

Geometrinya digandakan di lima tempat itu — kalau salah satu berubah, **ubah
semuanya**. Sumber kebenarannya `favicon.svg`.

## Membuat ulang PNG

`apple-touch-icon.png` diturunkan dari `favicon.svg` dengan sudut membulat dibuang
(iOS memberi sudutnya sendiri; kalau tidak dibuang, sudutnya jadi dobel):

```bash
cd web/public
sed 's/ rx="7"//' favicon.svg > /tmp/fullbleed.svg
rsvg-convert -w 180 -h 180 /tmp/fullbleed.svg -o apple-touch-icon.png
```

## Aturan pakai

- **Jangan** taruh lambang ini di portal pelanggan. Portal itu wajah **ISP**
  ke pelanggannya, bukan wajah kita — pelanggan berlangganan ke ISP, bukan ke
  penyedia perangkat lunaknya.
- Ruang kosong minimum di sekeliling kunci merek = tinggi tile-nya.
- Ukuran terkecil yang masih sah: **16px** untuk glyph, **96px** untuk kunci merek
  berwordmark (di bawah itu "Console" tak terbaca — pakai glyph saja).
- Di latar gelap, glyph jadi putih polos; tile gradasinya sudah cukup kontras dan
  tak perlu diubah.
