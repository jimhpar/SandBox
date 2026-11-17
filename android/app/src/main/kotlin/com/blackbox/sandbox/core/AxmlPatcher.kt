package com.blackbox.sandbox.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * AxmlPatcher
 * Safely parses and patches Android Binary XML (AndroidManifest.xml) inside an APK:
 * - Renames manifest `package` attribute to `newPackage`
 * - Expands relative class names (`.MainActivity` -> `oldPackage.MainActivity`) on
 *   <application>, <activity>, <activity-alias>, <service>, <receiver>, <provider>
 * - Renames <provider> `authorities` so clones never collide on ContentProviders
 * - Renames app-owned <permission> names to prevent duplicate-permission install failures
 */
object AxmlPatcher {

    private const val RES_XML_TYPE = 0x0003
    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val RES_XML_START_ELEMENT_TYPE = 0x0102

    private const val UTF8_FLAG = 1 shl 8
    private const val TYPE_STRING = 0x03

    private val COMPONENT_TAGS = setOf(
        "application", "activity", "activity-alias", "service", "receiver", "provider"
    )

    fun patchManifest(
        axmlBytes: ByteArray,
        oldPackage: String,
        newPackage: String,
        cloneSuffix: String
    ): ByteArray {
        val buf = ByteBuffer.wrap(axmlBytes).order(ByteOrder.LITTLE_ENDIAN)
        val fileType = buf.getShort(0).toInt() and 0xFFFF
        val fileHeaderSize = buf.getShort(2).toInt() and 0xFFFF
        if (fileType != RES_XML_TYPE) {
            throw IllegalArgumentException("Not a valid binary AXML file (type=$fileType)")
        }

        // Locate StringPool chunk right after file header
        val spOffset = fileHeaderSize
        val spType = buf.getShort(spOffset).toInt() and 0xFFFF
        val spHeaderSize = buf.getShort(spOffset + 2).toInt() and 0xFFFF
        val spChunkSize = buf.getInt(spOffset + 4)
        if (spType != RES_STRING_POOL_TYPE) {
            throw IllegalArgumentException("Expected StringPool chunk at $spOffset, found $spType")
        }

        val stringCount = buf.getInt(spOffset + 8)
        val styleCount = buf.getInt(spOffset + 12)
        val flags = buf.getInt(spOffset + 16)
        val stringsStart = buf.getInt(spOffset + 20)
        val stylesStart = buf.getInt(spOffset + 24)
        val isUtf8 = (flags and UTF8_FLAG) != 0

        val strings = MutableList(stringCount) { "" }
        val stringDataBase = spOffset + stringsStart
        for (i in 0 until stringCount) {
            val strRelOffset = buf.getInt(spOffset + spHeaderSize + i * 4)
            strings[i] = readPoolString(axmlBytes, stringDataBase + strRelOffset, isUtf8)
        }

        // Copy raw style offsets & style bytes if any exist
        val styleOffsetsBytes = if (styleCount > 0) {
            val start = spOffset + spHeaderSize + stringCount * 4
            axmlBytes.copyOfRange(start, start + styleCount * 4)
        } else {
            ByteArray(0)
        }
        val stylesDataBytes = if (styleCount > 0 && stylesStart > 0) {
            axmlBytes.copyOfRange(spOffset + stylesStart, spOffset + spChunkSize)
        } else {
            ByteArray(0)
        }

        // Copy the rest of the AXML (resource map + XML tree nodes) so we can mutate attribute string indices
        val tailOffset = spOffset + spChunkSize
        val tailBytes = axmlBytes.copyOfRange(tailOffset, axmlBytes.size)
        val tailBuf = ByteBuffer.wrap(tailBytes).order(ByteOrder.LITTLE_ENDIAN)

        fun addString(value: String): Int {
            val existing = strings.indexOf(value)
            if (existing >= 0) return existing
            strings.add(value)
            return strings.size - 1
        }

        // Walk XML chunks in tailBytes
        var pos = 0
        while (pos + 8 <= tailBytes.size) {
            val cType = tailBuf.getShort(pos).toInt() and 0xFFFF
            val cHeaderSize = tailBuf.getShort(pos + 2).toInt() and 0xFFFF
            val cSize = tailBuf.getInt(pos + 4)
            if (cSize <= 0 || pos + cSize > tailBytes.size) break

            if (cType == RES_XML_START_ELEMENT_TYPE && pos + cHeaderSize + 20 <= tailBytes.size) {
                val extOffset = pos + cHeaderSize
                val nameIdx = tailBuf.getInt(extOffset + 4)
                val attrStart = tailBuf.getShort(extOffset + 8).toInt() and 0xFFFF
                val attrSize = tailBuf.getShort(extOffset + 10).toInt() and 0xFFFF
                val attrCount = tailBuf.getShort(extOffset + 12).toInt() and 0xFFFF
                val tagName = strings.getOrNull(nameIdx) ?: ""

                val attrsBase = extOffset + attrStart
                for (a in 0 until attrCount) {
                    val attrOff = attrsBase + a * attrSize
                    if (attrOff + 20 > pos + cSize) break

                    val attrNameIdx = tailBuf.getInt(attrOff + 4)
                    val attrRawValIdx = tailBuf.getInt(attrOff + 8)
                    val valType = tailBuf.get(attrOff + 15).toInt() and 0xFF
                    val valData = tailBuf.getInt(attrOff + 16)

                    val attrName = strings.getOrNull(attrNameIdx) ?: ""
                    val isStringAttr = (valType == TYPE_STRING && valData in 0 until strings.size)
                    val strVal = if (isStringAttr) strings[valData] else if (attrRawValIdx in 0 until strings.size) strings[attrRawValIdx] else null

                    fun updateAttrString(newVal: String) {
                        val newIdx = addString(newVal)
                        if (attrRawValIdx >= 0) tailBuf.putInt(attrOff + 8, newIdx)
                        if (valType == TYPE_STRING) tailBuf.putInt(attrOff + 16, newIdx)
                    }

                    if (tagName == "manifest" && attrName == "package" && strVal != null) {
                        updateAttrString(newPackage)
                    } else if (tagName in COMPONENT_TAGS && (attrName == "name" || attrName == "targetActivity") && strVal != null) {
                        if (strVal.startsWith(".")) {
                            updateAttrString(oldPackage + strVal)
                        } else if (!strVal.contains(".")) {
                            updateAttrString("$oldPackage.$strVal")
                        }
                    } else if (tagName == "provider" && attrName == "authorities" && strVal != null) {
                        val updatedAuth = strVal.split(";").joinToString(";") { part ->
                            if (part.contains(oldPackage)) part.replace(oldPackage, newPackage)
                            else "$part.$cloneSuffix"
                        }
                        updateAttrString(updatedAuth)
                    } else if ((tagName == "permission" || tagName == "uses-permission" || tagName == "uses-permission-sdk-23") && attrName == "name" && strVal != null) {
                        if (strVal.startsWith("$oldPackage.")) {
                            updateAttrString(strVal.replaceFirst(oldPackage, newPackage))
                        }
                    } else if (tagName == "application" && attrName == "label" && isStringAttr && strVal != null) {
                        if (!strVal.endsWith(")")) {
                            updateAttrString("$strVal ($cloneSuffix)")
                        }
                    }
                }
            }
            pos += cSize
        }

        // Rebuild StringPool chunk
        val newSpChunk = buildStringPoolChunk(strings, styleCount, flags, isUtf8, styleOffsetsBytes, stylesDataBytes)
        val totalSize = fileHeaderSize + newSpChunk.size + tailBytes.size

        val out = ByteArrayOutputStream(totalSize)
        val newFileHeader = axmlBytes.copyOfRange(0, fileHeaderSize)
        ByteBuffer.wrap(newFileHeader).order(ByteOrder.LITTLE_ENDIAN).putInt(4, totalSize)
        out.write(newFileHeader)
        out.write(newSpChunk)
        out.write(tailBytes)
        return out.toByteArray()
    }

    private fun readPoolString(data: ByteArray, offset: Int, isUtf8: Boolean): String {
        var o = offset
        if (isUtf8) {
            // Skip char length (1 or 2 bytes)
            val c1 = data[o++].toInt() and 0xFF
            if ((c1 and 0x80) != 0) o++
            // Read byte length (1 or 2 bytes)
            var bLen = data[o++].toInt() and 0xFF
            if ((bLen and 0x80) != 0) {
                val b2 = data[o++].toInt() and 0xFF
                bLen = ((bLen and 0x7F) shl 8) or b2
            }
            return String(data, o, bLen, Charsets.UTF_8)
        } else {
            var cLen = ((data[o + 1].toInt() and 0xFF) shl 8) or (data[o].toInt() and 0xFF)
            o += 2
            if ((cLen and 0x8000) != 0) {
                val low = ((data[o + 1].toInt() and 0xFF) shl 8) or (data[o].toInt() and 0xFF)
                o += 2
                cLen = ((cLen and 0x7FFF) shl 16) or low
            }
            return String(data, o, cLen * 2, Charsets.UTF_16LE)
        }
    }

    private fun buildStringPoolChunk(
        strings: List<String>,
        styleCount: Int,
        flags: Int,
        isUtf8: Boolean,
        styleOffsetsBytes: ByteArray,
        stylesDataBytes: ByteArray
    ): ByteArray {
        val headerSize = 28
        val newStringCount = strings.size
        val offsetsTableSize = newStringCount * 4 + styleOffsetsBytes.size

        val encodedOffsets = IntArray(newStringCount)
        val strData = ByteArrayOutputStream()

        for (i in 0 until newStringCount) {
            encodedOffsets[i] = strData.size()
            val s = strings[i]
            if (isUtf8) {
                val bytes = s.toByteArray(Charsets.UTF_8)
                writeLen8(strData, s.length)
                writeLen8(strData, bytes.size)
                strData.write(bytes)
                strData.write(0)
            } else {
                val bytes = s.toByteArray(Charsets.UTF_16LE)
                writeLen16(strData, s.length)
                strData.write(bytes)
                strData.write(0)
                strData.write(0)
            }
        }
        // Pad string data to 4-byte boundary
        while (strData.size() % 4 != 0) {
            strData.write(0)
        }

        val stringsStart = headerSize + offsetsTableSize
        val newStylesStart = if (styleCount > 0 && stylesDataBytes.isNotEmpty()) {
            stringsStart + strData.size()
        } else {
            0
        }
        var rawChunkSize = stringsStart + strData.size() + stylesDataBytes.size
        val padding = (4 - (rawChunkSize % 4)) % 4
        rawChunkSize += padding

        val header = ByteBuffer.allocate(headerSize).order(ByteOrder.LITTLE_ENDIAN)
        header.putShort(RES_STRING_POOL_TYPE.toShort())
        header.putShort(headerSize.toShort())
        header.putInt(rawChunkSize)
        header.putInt(newStringCount)
        header.putInt(styleCount)
        header.putInt(flags)
        header.putInt(stringsStart)
        header.putInt(newStylesStart)

        val offBuf = ByteBuffer.allocate(newStringCount * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (off in encodedOffsets) offBuf.putInt(off)

        val out = ByteArrayOutputStream(rawChunkSize)
        out.write(header.array())
        out.write(offBuf.array())
        out.write(styleOffsetsBytes)
        out.write(strData.toByteArray())
        out.write(stylesDataBytes)
        for (p in 0 until padding) out.write(0)
        return out.toByteArray()
    }

    private fun writeLen8(out: ByteArrayOutputStream, len: Int) {
        if (len > 0x7F) {
            out.write(((len shr 8) and 0x7F) or 0x80)
        }
        out.write(len and 0xFF)
    }

    private fun writeLen16(out: ByteArrayOutputStream, len: Int) {
        if (len > 0x7FFF) {
            val high = ((len shr 16) and 0x7FFF) or 0x8000
            out.write(high and 0xFF)
            out.write((high shr 8) and 0xFF)
        }
        out.write(len and 0xFF)
        out.write((len shr 8) and 0xFF)
    }
}
