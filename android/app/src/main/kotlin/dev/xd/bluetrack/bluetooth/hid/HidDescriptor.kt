package dev.xd.bluetrack.bluetooth.hid

object HidDescriptor {
    object Tag {
        const val USAGE_PAGE: Byte = 0x05
        const val USAGE: Byte = 0x09
        const val COLLECTION: Byte = 0xA1.toByte()
        const val USAGE_MIN: Byte = 0x19
        const val USAGE_MAX: Byte = 0x29
        const val LOGICAL_MIN: Byte = 0x15
        const val LOGICAL_MAX: Byte = 0x25
        const val REPORT_ID: Byte = 0x85.toByte()
        const val REPORT_COUNT: Byte = 0x95.toByte()
        const val REPORT_SIZE: Byte = 0x75
        const val INPUT: Byte = 0x81.toByte()
        const val END_COLLECTION: Byte = 0xC0.toByte()
    }

    object UsagePage {
        const val GENERIC_DESKTOP: Byte = 0x01
        const val BUTTON: Byte = 0x09
    }

    object Usage {
        const val MOUSE: Byte = 0x02
        const val POINTER: Byte = 0x01
        const val X: Byte = 0x30
        const val Y: Byte = 0x31
        const val WHEEL: Byte = 0x38
    }

    object Collection {
        const val PHYSICAL: Byte = 0x00
        const val APPLICATION: Byte = 0x01
    }

    object Input {
        const val DAT_VAR_ABS: Byte = 0x02
        const val CON: Byte = 0x01
        const val DAT_VAR_REL: Byte = 0x06
    }

    fun getDescriptor(): ByteArray =
        byteArrayOf(
            Tag.USAGE_PAGE,
            UsagePage.GENERIC_DESKTOP,
            Tag.USAGE,
            Usage.MOUSE,
            Tag.COLLECTION,
            Collection.APPLICATION,
            Tag.REPORT_ID,
            1.toByte(),
            Tag.USAGE,
            Usage.POINTER,
            Tag.COLLECTION,
            Collection.PHYSICAL,
            Tag.USAGE_PAGE,
            UsagePage.BUTTON,
            Tag.USAGE_MIN,
            1.toByte(),
            Tag.USAGE_MAX,
            3.toByte(),
            Tag.LOGICAL_MIN,
            0,
            Tag.LOGICAL_MAX,
            1,
            Tag.REPORT_SIZE,
            1,
            Tag.REPORT_COUNT,
            3,
            Tag.INPUT,
            Input.DAT_VAR_ABS,
            Tag.REPORT_SIZE,
            5,
            Tag.REPORT_COUNT,
            1,
            Tag.INPUT,
            Input.CON,
            Tag.USAGE_PAGE,
            UsagePage.GENERIC_DESKTOP,
            Tag.USAGE,
            Usage.X,
            Tag.USAGE,
            Usage.Y,
            Tag.USAGE,
            Usage.WHEEL,
            Tag.LOGICAL_MIN,
            0x81.toByte(),
            Tag.LOGICAL_MAX,
            0x7F.toByte(),
            Tag.REPORT_SIZE,
            8,
            Tag.REPORT_COUNT,
            3,
            Tag.INPUT,
            Input.DAT_VAR_REL,
            Tag.END_COLLECTION,
            Tag.END_COLLECTION,
        )
}
