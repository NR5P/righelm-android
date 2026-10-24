package com.righelm.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    @Test fun proofMatchesServer() {
        // Python: hmac.new(b"secret", b"ftdx3000:0123456789abcdef:CAT", sha256)
        assertEquals(
            "a24b82faf7ccc57095b516d26cd00f51d56966dc2d44a1f21efa8b5b0a850ab0",
            RemoteProtocol.proof("secret", "0123456789abcdef", "CAT"),
        )
        assertEquals("-", RemoteProtocol.proof("", "0123456789abcdef", "CAT"))
    }

    @Test fun beacon() {
        val b = ServerBeacon.parse("FTDX3000-REMOTE v1;id=b7974e2e3ab6a5b1;name=thinkpad;port=4533;audio=1;auth=1;mic=0;radio=connected", "192.168.1.5")
        assertEquals("thinkpad", b?.name)
        assertEquals("192.168.1.5", b?.host)
        assertEquals(4533, b?.port)
        assertEquals(true, b?.auth)
        assertEquals(false, b?.mic)
        assertEquals("connected", b?.radio)
        assertNull(ServerBeacon.parse("FTDX3000-DISCOVER", "1.2.3.4"))
    }

    @Test fun audioPacketRoundTrip() {
        val p = AudioPacket.encode(AudioPacket.RX, 42, 7, shortArrayOf(1, -2, 3))
        assertEquals(22, p.size)
        val d = AudioPacket.decode(p)!!
        assertEquals(AudioPacket.RX, d.kind)
        assertEquals(42L, d.session)
        assertEquals(7L, d.seq)
        assertEquals(listOf<Short>(1, -2, 3), d.samples.toList())
        assertNull(AudioPacket.decode("nope".toByteArray()))
        // Sessions and sequence numbers use all 64 and 32 bits.
        val big = AudioPacket.decode(AudioPacket.encode(AudioPacket.TX, -1L, 0xFFFF_FFFFL, ShortArray(0)))!!
        assertEquals(-1L, big.session)
        assertEquals(0xFFFF_FFFFL, big.seq)
    }

    @Test fun sweepCoversSubnet() {
        val iface = NetInterface("wlan0", 0xC0A8_0AEAL, 0xFFFF_FF00L, 0xC0A8_0AFFL)
        val hosts = Discovery.sweep(iface)
        assertEquals(253, hosts.size)
        assertTrue(0xC0A8_0ADFL in hosts) // .223
        assertFalse(0xC0A8_0AEAL in hosts) // ourselves
        val wide = NetInterface("wlan0", 0x0A00_0105L, 0xFFFF_0000L, 0x0A00_FFFFL)
        assertEquals(253, Discovery.sweep(wide).size)
        assertEquals("192.168.10.223", ipString(0xC0A8_0ADFL))
    }
}

class CatTest {
    @Test fun transmitClassification() {
        for (f in listOf("TX1;", "TX2;", "MX1;", "AC002;", "KY1;", "PB01;", "VX1;", "EN1;")) {
            assertTrue(f, CatCommand.isTransmit(f))
        }
        for (f in listOf("TX0;", "MX0;", "AC001;", "AC000;", "PB00;", "VX0;", "FA14074000;", "TX;", "MD02;")) {
            assertFalse(f, CatCommand.isTransmit(f))
        }
    }

    @Test fun readback() {
        assertEquals("AG0;", CatCommand.readback("AG0128;"))
        assertEquals("BP01;", CatCommand.readback("BP01040;"))
        assertEquals("FA;", CatCommand.readback("FA07074000;"))
        assertEquals("EX053;", CatCommand.readback("EX0532;"))
        assertEquals("RM5", CatCommand.answerKey("RM5161;"))
    }

    @Test fun framer() {
        val f = Framer()
        assertEquals(emptyList<String>(), f.push("FA0707".toByteArray()))
        assertEquals(listOf("FA07074000;", "MD03;"), f.push("4000;\r\nMD03;".toByteArray()))
    }

    @Test fun frames() {
        assertEquals(listOf("AB;", "SV;"), CatCommand.frames("AB;SV;"))
        assertEquals(listOf("FA14074000;"), CatCommand.frames(" FA14074000 ;;x;"))
    }

    @Test fun blockedTransmitNeverQueues() {
        val c = CatClient("127.0.0.1", password = "")
        c.isTransmitAllowed = { false }
        c.send("TX1;")
        assertFalse(c.keyedByUs)
        c.isTransmitAllowed = { true }
        c.send("TX1;")
        assertTrue(c.keyedByUs)
        c.send("TX0;")
        assertFalse(c.keyedByUs)
    }
}

class StateTest {
    @Test fun parsesIF() {
        val s = RadioState()
        assertTrue(s.apply("IF00114074000+012010200000;"))
        assertEquals(14_074_000, s.freqA)
        assertEquals(120, s.infoA.clarOffset)
        assertTrue(s.rxClar)
        assertFalse(s.txClar)
        assertEquals(Mode.USB, s.modeA)
        assertTrue("IF" in s.seen)
    }

    @Test fun commonAnswers() {
        val s = RadioState()
        for (f in listOf("FA07074000;", "FB14074000;", "MD03;", "AG0128;", "SM0130;", "RM5161;", "TX1;", "FT1;", "FR0;", "EX0383;", "NA01;", "SH010;", "IS0-0120;", "RF04;")) {
            assertTrue(f, s.apply(f))
        }
        assertEquals(7_074_000, s.freqA)
        assertEquals(Mode.CW, s.modeA)
        assertEquals(128, s.afGain)
        assertEquals(130, s.sMeter)
        assertEquals(161, s.meter(Meter.PO))
        assertTrue(s.transmitting)
        assertTrue(s.split)
        assertEquals("3", s.menu[38])
        assertEquals(500, s.widthHz)
        assertEquals(-120, s.ifShift)
        assertEquals('4', s.roofing)
        assertTrue(s.apply("MD0C;"))
        assertEquals(WidthClass.RTTY, s.widthClass(Mode.DATA_USB))
        assertFalse(s.apply("?;"))
        assertFalse(s.apply("ZZ1;"))
    }

    @Test fun snapshotsAreIndependent() {
        val s = RadioState()
        s.apply("EX0532;")
        val snap = s.copy()
        s.apply("EX0530;")
        assertEquals(2, snap.micSel(53))
        assertEquals(0, s.micSel(53))
    }

    @Test fun meterText() {
        assertEquals("S0", MeterCal.sText(0))
        assertEquals("S9", MeterCal.sText(130))
        assertEquals("S9", MeterCal.sText(135))
        assertEquals("S9+20", MeterCal.sText(172))
        assertEquals(100.0, MeterCal.interpolate(MeterCal.po, 161), 0.01)
        assertEquals("7.137.040", Frequency.format(7_137_040))
        assertEquals("FB14074000;", Frequency.setCommand(vfoB = true, hz = 14_074_000))
        assertEquals("BS05;", Band.all[5].setCommand)
    }
}
