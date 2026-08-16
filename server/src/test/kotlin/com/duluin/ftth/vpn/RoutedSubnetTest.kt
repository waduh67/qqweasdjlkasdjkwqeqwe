package com.duluin.ftth.vpn

import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.vpn.domain.model.RoutedSubnet
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Menguji blok alamat di belakang peer — murni domain, tanpa Spring maupun database.
 * Fokusnya pada dua hal yang menggigit di lapangan: normalisasi alamat pelanggan menjadi blok,
 * dan deteksi irisan (yang lolos dari UNIQUE tapi merusak tabel rute hub diam-diam).
 */
class RoutedSubnetTest {

    @Test
    fun `parse CIDR valid`() {
        val subnet = RoutedSubnet.parse("10.20.0.0/16")
        assertThat(subnet.prefix).isEqualTo(16)
        assertThat(subnet.cidr).isEqualTo("10.20.0.0/16")
        assertThat(subnet.netmask()).isEqualTo("255.255.0.0")
    }

    @Test
    fun `parse menormalisasi alamat pelanggan ke blok kolamnya`() {
        // Yang paling sering ada di tangan operator adalah alamat SATU pelanggan dari radacct,
        // bukan blok kolamnya — menempelkannya apa adanya harus tetap menghasilkan blok benar.
        assertThat(RoutedSubnet.parse("10.20.255.254/16").cidr).isEqualTo("10.20.0.0/16")
    }

    @Test
    fun `parse menerima satu perangkat tunggal`() {
        val subnet = RoutedSubnet.parse("192.168.88.1/32")
        assertThat(subnet.cidr).isEqualTo("192.168.88.1/32")
        assertThat(subnet.netmask()).isEqualTo("255.255.255.255")
    }

    @Test
    fun `parse menolak blok yang lebih lebar dari slash 8`() {
        // Rute selebar ini dipasang di kernel hub akan menelan trafik hub itu sendiri —
        // termasuk SSH operator yang sedang memasangnya.
        assertThatThrownBy { RoutedSubnet.parse("0.0.0.0/0") }
            .isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { RoutedSubnet.parse("10.0.0.0/7") }
            .isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `parse menolak loopback dan multicast`() {
        assertThatThrownBy { RoutedSubnet.parse("127.0.0.0/8") }
            .isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { RoutedSubnet.parse("224.0.0.0/24") }
            .isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `parse menolak yang bukan IPv4 dotted-quad berprefix`() {
        assertThatThrownBy { RoutedSubnet.parse("10.20.0.0") }
            .isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { RoutedSubnet.parse("10.20.0/16") }
            .isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { RoutedSubnet.parse("10.20.0.300/16") }
            .isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `overlaps menangkap blok yang saling memuat`() {
        val pool = RoutedSubnet.parse("10.20.0.0/16")
        val slice = RoutedSubnet.parse("10.20.5.0/24")

        // Dua arah harus tertangkap: urutan pendaftaran operator tak boleh menentukan hasilnya.
        assertThat(pool.overlaps(slice)).isTrue()
        assertThat(slice.overlaps(pool)).isTrue()
    }

    @Test
    fun `overlaps membiarkan blok yang benar-benar terpisah`() {
        val a = RoutedSubnet.parse("10.20.0.0/16")
        val b = RoutedSubnet.parse("10.21.0.0/16")

        assertThat(a.overlaps(b)).isFalse()
        assertThat(b.overlaps(a)).isFalse()
    }

    @Test
    fun `contains menentukan keanggotaan blok`() {
        val pool = RoutedSubnet.parse("10.20.0.0/16")
        assertThat(pool.contains("10.20.255.254")).isTrue()
        assertThat(pool.contains("10.21.0.1")).isFalse()
    }
}
