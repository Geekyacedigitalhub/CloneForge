package com.cloneforge.app

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Merges `RES_TABLE_TYPE_TYPE` (`0x0201`) configuration chunks (such as `-xxhdpi`
 * drawables and `-en` localized strings) from `split_config.*.apk` `resources.arsc`
 * files into `base.apk`'s `resources.arsc` (`packageId == 0x7f`).
 *
 * In Android App Bundles (AAB), density and language resource table entries are stripped
 * out of `base.apk`'s `resources.arsc` and placed in `split_config.<density/lang>.apk`.
 * Merging those `0x0201` chunks and their Global String Pool values into `base.apk`'s
 * `resources.arsc` prevents `Resources$NotFoundException` crashes at startup when splits
 * are merged into a standalone APK.
 */
class ArscSplitMerger {

    private val little = ByteOrder.LITTLE_ENDIAN

    fun mergeBaseSplits(baseArsc: ByteArray, splitArscs: List<ByteArray>): ByteArray {
        if (splitArscs.isEmpty() || baseArsc.size < 24) return baseArsc
        return runCatching {
            doMerge(baseArsc, splitArscs)
        }.getOrDefault(baseArsc)
    }

    private fun doMerge(baseArsc: ByteArray, splitArscs: List<ByteArray>): ByteArray {
        if (u16(baseArsc, 0) != 0x0002) return baseArsc
        val rootHeaderSize = u16(baseArsc, 2)
        val rootSize = u32(baseArsc, 4)
        if (rootHeaderSize < 12 || rootSize > baseArsc.size) return baseArsc

        var globalPoolOffset = -1
        var globalPoolHeaderSize = 0
        var globalPoolSize = 0
        var basePkgOffset = -1
        var basePkgHeaderSize = 0
        var basePkgSize = 0

        var offset = rootHeaderSize
        while (offset + 8 <= baseArsc.size) {
            val type = u16(baseArsc, offset)
            val headerSize = u16(baseArsc, offset + 2)
            val size = u32(baseArsc, offset + 4)
            if (size <= 0 || size < headerSize || offset + size > baseArsc.size) break

            if (type == 0x0001 && globalPoolOffset < 0) {
                globalPoolOffset = offset
                globalPoolHeaderSize = headerSize
                globalPoolSize = size
            } else if (type == 0x0200 && offset + 12 <= baseArsc.size) {
                val pkgId = u32(baseArsc, offset + 8)
                if (pkgId == 0x7f && basePkgOffset < 0) {
                    basePkgOffset = offset
                    basePkgHeaderSize = headerSize
                    basePkgSize = size
                }
            }
            offset += size
        }

        if (globalPoolOffset < 0 || basePkgOffset < 0 || basePkgOffset < globalPoolOffset + globalPoolSize) {
            return baseArsc
        }

        val origGlobalStringCount = u32(baseArsc, globalPoolOffset + 8)
        val origGlobalStyleCount = u32(baseArsc, globalPoolOffset + 12)
        val origGlobalFlags = u32(baseArsc, globalPoolOffset + 16)
        val origGlobalStringsStart = u32(baseArsc, globalPoolOffset + 20)
        val origGlobalStylesStart = u32(baseArsc, globalPoolOffset + 24)
        val isGlobalUtf8 = (origGlobalFlags and 0x100) != 0

        val baseKeyStringsOffsetRel = u32(baseArsc, basePkgOffset + 276)
        if (baseKeyStringsOffsetRel <= 0 || basePkgOffset + baseKeyStringsOffsetRel >= basePkgOffset + basePkgSize) {
            return baseArsc
        }
        val baseKeyStrings = readStringPoolStrings(baseArsc, basePkgOffset + baseKeyStringsOffsetRel)
        val baseKeyIndexByName = HashMap<String, Int>(baseKeyStrings.size * 2)
        for (i in baseKeyStrings.indices) {
            baseKeyIndexByName.putIfAbsent(baseKeyStrings[i], i)
        }

        val addedGlobalStrings = mutableListOf<String>()
        val addedGlobalStringIndex = HashMap<String, Int>()

        fun mapGlobalString(value: String): Int {
            return addedGlobalStringIndex.getOrPut(value) {
                val newIdx = origGlobalStringCount + addedGlobalStrings.size
                addedGlobalStrings.add(value)
                newIdx
            }
        }

        val appendedTypeChunks = ByteArrayOutputStream()
        var chunksMerged = 0

        for (splitArsc in splitArscs) {
            if (splitArsc.size < 24 || u16(splitArsc, 0) != 0x0002) continue
            val splitRootHeader = u16(splitArsc, 2)
            var splitGlobalPoolOffset = -1
            var splitPkgOffset = -1
            var splitPkgHeaderSize = 0
            var splitPkgSize = 0

            var sOff = splitRootHeader
            while (sOff + 8 <= splitArsc.size) {
                val cType = u16(splitArsc, sOff)
                val cHeader = u16(splitArsc, sOff + 2)
                val cSize = u32(splitArsc, sOff + 4)
                if (cSize <= 0 || cSize < cHeader || sOff + cSize > splitArsc.size) break
                if (cType == 0x0001 && splitGlobalPoolOffset < 0) {
                    splitGlobalPoolOffset = sOff
                } else if (cType == 0x0200 && sOff + 12 <= splitArsc.size) {
                    val pkgId = u32(splitArsc, sOff + 8)
                    if (pkgId == 0x7f && splitPkgOffset < 0) {
                        splitPkgOffset = sOff
                        splitPkgHeaderSize = cHeader
                        splitPkgSize = cSize
                    }
                }
                sOff += cSize
            }

            if (splitGlobalPoolOffset < 0 || splitPkgOffset < 0) continue

            val splitGlobalStrings = readStringPoolStrings(splitArsc, splitGlobalPoolOffset)
            val splitKeyOffsetRel = u32(splitArsc, splitPkgOffset + 276)
            if (splitKeyOffsetRel <= 0 || splitPkgOffset + splitKeyOffsetRel >= splitPkgOffset + splitPkgSize) continue
            val splitKeyStrings = readStringPoolStrings(splitArsc, splitPkgOffset + splitKeyOffsetRel)

            var pkgChildOff = splitPkgOffset + splitPkgHeaderSize
            val pkgEnd = splitPkgOffset + splitPkgSize
            while (pkgChildOff + 8 <= pkgEnd) {
                val cType = u16(splitArsc, pkgChildOff)
                val cHeader = u16(splitArsc, pkgChildOff + 2)
                val cSize = u32(splitArsc, pkgChildOff + 4)
                if (cSize <= 0 || cSize < cHeader || pkgChildOff + cSize > pkgEnd) break

                if (cType == 0x0201 && cSize >= 20) {
                    val typeChunk = splitArsc.copyOfRange(pkgChildOff, pkgChildOff + cSize)
                    if (remapTypeChunk(
                            typeChunk = typeChunk,
                            splitGlobalStrings = splitGlobalStrings,
                            splitKeyStrings = splitKeyStrings,
                            baseKeyIndexByName = baseKeyIndexByName,
                            mapGlobalString = ::mapGlobalString
                        )
                    ) {
                        appendedTypeChunks.write(typeChunk)
                        chunksMerged++
                    }
                }
                pkgChildOff += cSize
            }
        }

        if (chunksMerged == 0) return baseArsc

        val newGlobalPool = appendStringsToGlobalPool(
            arsc = baseArsc,
            poolOffset = globalPoolOffset,
            poolHeaderSize = globalPoolHeaderSize,
            poolSize = globalPoolSize,
            origStringCount = origGlobalStringCount,
            origStyleCount = origGlobalStyleCount,
            origFlags = origGlobalFlags,
            origStringsStart = origGlobalStringsStart,
            origStylesStart = origGlobalStylesStart,
            utf8 = isGlobalUtf8,
            addedStrings = addedGlobalStrings
        )

        val extraTypeBytes = appendedTypeChunks.toByteArray()
        val beforePool = baseArsc.copyOfRange(0, globalPoolOffset)
        val betweenPoolAndPkgEnd = baseArsc.copyOfRange(
            globalPoolOffset + globalPoolSize,
            basePkgOffset + basePkgSize
        )
        val afterPkg = baseArsc.copyOfRange(basePkgOffset + basePkgSize, baseArsc.size)

        val out = ByteArray(
            beforePool.size +
                newGlobalPool.size +
                betweenPoolAndPkgEnd.size +
                extraTypeBytes.size +
                afterPkg.size
        )
        var pos = 0
        System.arraycopy(beforePool, 0, out, pos, beforePool.size)
        pos += beforePool.size
        System.arraycopy(newGlobalPool, 0, out, pos, newGlobalPool.size)
        pos += newGlobalPool.size
        val newBasePkgOffset = pos + (basePkgOffset - (globalPoolOffset + globalPoolSize))
        System.arraycopy(betweenPoolAndPkgEnd, 0, out, pos, betweenPoolAndPkgEnd.size)
        pos += betweenPoolAndPkgEnd.size
        System.arraycopy(extraTypeBytes, 0, out, pos, extraTypeBytes.size)
        pos += extraTypeBytes.size
        System.arraycopy(afterPkg, 0, out, pos, afterPkg.size)

        putU32(out, 4, out.size)
        putU32(out, newBasePkgOffset + 4, basePkgSize + extraTypeBytes.size)

        return out
    }

    private fun remapTypeChunk(
        typeChunk: ByteArray,
        splitGlobalStrings: List<String>,
        splitKeyStrings: List<String>,
        baseKeyIndexByName: Map<String, Int>,
        mapGlobalString: (String) -> Int
    ): Boolean {
        val headerSize = u16(typeChunk, 2)
        val chunkSize = u32(typeChunk, 4)
        if (headerSize < 20 || chunkSize > typeChunk.size) return false

        val flags = typeChunk[9].toInt() and 0xff
        val isSparse = (flags and 0x01) != 0
        val isOffset16 = (flags and 0x02) != 0
        val entryCount = u32(typeChunk, 12)
        val entriesStart = u32(typeChunk, 16)
        if (entriesStart < headerSize || entriesStart > chunkSize) return false

        val offsetsBase = headerSize
        for (i in 0 until entryCount) {
            val entryRelOffset: Int = when {
                isSparse && isOffset16 -> {
                    val offPos = offsetsBase + i * 4
                    if (offPos + 4 > entriesStart) return false
                    (u16(typeChunk, offPos + 2) and 0xffff) * 4
                }
                isSparse -> {
                    val offPos = offsetsBase + i * 8
                    if (offPos + 8 > entriesStart) return false
                    u32(typeChunk, offPos + 4)
                }
                isOffset16 -> {
                    val offPos = offsetsBase + i * 2
                    if (offPos + 2 > entriesStart) return false
                    val raw16 = u16(typeChunk, offPos)
                    if (raw16 == 0xffff) continue
                    raw16 * 4
                }
                else -> {
                    val offPos = offsetsBase + i * 4
                    if (offPos + 4 > entriesStart) return false
                    val raw32 = u32(typeChunk, offPos)
                    if (raw32 == -1) continue
                    raw32
                }
            }

            val entryPos = entriesStart + entryRelOffset
            if (entryPos < entriesStart || entryPos + 8 > chunkSize) continue

            val entryFlags = u16(typeChunk, entryPos + 2)
            val isCompact = (entryFlags and 0x0008) != 0
            val isComplex = (entryFlags and 0x0001) != 0

            if (isCompact) {
                val oldKey = u16(typeChunk, entryPos)
                val keyName = splitKeyStrings.getOrNull(oldKey)
                val mappedKey = if (keyName != null) baseKeyIndexByName[keyName] ?: oldKey else oldKey
                if (mappedKey in 0..0xffff) {
                    putU16(typeChunk, entryPos, mappedKey)
                }
                val dataType = (entryFlags ushr 8) and 0xff
                if (dataType == 0x03) {
                    val oldStrIdx = u32(typeChunk, entryPos + 4)
                    val strVal = splitGlobalStrings.getOrNull(oldStrIdx)
                    if (strVal != null) {
                        putU32(typeChunk, entryPos + 4, mapGlobalString(strVal))
                    }
                }
            } else if (!isComplex) {
                if (entryPos + 16 > chunkSize) continue
                val oldKey = u32(typeChunk, entryPos + 4)
                val keyName = splitKeyStrings.getOrNull(oldKey)
                if (keyName != null) {
                    baseKeyIndexByName[keyName]?.let { putU32(typeChunk, entryPos + 4, it) }
                }
                val dataType = typeChunk[entryPos + 11].toInt() and 0xff
                if (dataType == 0x03) {
                    val oldStrIdx = u32(typeChunk, entryPos + 12)
                    val strVal = splitGlobalStrings.getOrNull(oldStrIdx)
                    if (strVal != null) {
                        putU32(typeChunk, entryPos + 12, mapGlobalString(strVal))
                    }
                }
            } else {
                val entrySize = u16(typeChunk, entryPos)
                if (entrySize < 16 || entryPos + entrySize > chunkSize) continue
                val oldKey = u32(typeChunk, entryPos + 4)
                val keyName = splitKeyStrings.getOrNull(oldKey)
                if (keyName != null) {
                    baseKeyIndexByName[keyName]?.let { putU32(typeChunk, entryPos + 4, it) }
                }
                val mapCount = u32(typeChunk, entryPos + 12)
                var mapPos = entryPos + entrySize
                for (m in 0 until mapCount) {
                    if (mapPos + 12 > chunkSize) break
                    val dataType = typeChunk[mapPos + 7].toInt() and 0xff
                    if (dataType == 0x03) {
                        val oldStrIdx = u32(typeChunk, mapPos + 8)
                        val strVal = splitGlobalStrings.getOrNull(oldStrIdx)
                        if (strVal != null) {
                            putU32(typeChunk, mapPos + 8, mapGlobalString(strVal))
                        }
                    }
                    mapPos += 12
                }
            }
        }
        return true
    }

    private fun appendStringsToGlobalPool(
        arsc: ByteArray,
        poolOffset: Int,
        poolHeaderSize: Int,
        poolSize: Int,
        origStringCount: Int,
        origStyleCount: Int,
        origFlags: Int,
        origStringsStart: Int,
        origStylesStart: Int,
        utf8: Boolean,
        addedStrings: List<String>
    ): ByteArray {
        if (addedStrings.isEmpty()) {
            return arsc.copyOfRange(poolOffset, poolOffset + poolSize)
        }

        val origStringOffsetsBytes = arsc.copyOfRange(
            poolOffset + poolHeaderSize,
            poolOffset + poolHeaderSize + origStringCount * 4
        )
        val origStyleOffsetsBytes = if (origStyleCount > 0) {
            arsc.copyOfRange(
                poolOffset + poolHeaderSize + origStringCount * 4,
                poolOffset + poolHeaderSize + (origStringCount + origStyleCount) * 4
            )
        } else {
            ByteArray(0)
        }

        val origStringsEndAbs = if (origStyleCount > 0 && origStylesStart > origStringsStart) {
            poolOffset + origStylesStart
        } else {
            poolOffset + poolSize
        }
        val origStringsBytes = arsc.copyOfRange(poolOffset + origStringsStart, origStringsEndAbs)
        val origStylesBytes = if (origStyleCount > 0 && origStylesStart > 0) {
            arsc.copyOfRange(poolOffset + origStylesStart, poolOffset + poolSize)
        } else {
            ByteArray(0)
        }

        val addedBlob = ByteArrayOutputStream()
        val addedOffsets = IntArray(addedStrings.size)
        for (i in addedStrings.indices) {
            addedOffsets[i] = origStringsBytes.size + addedBlob.size()
            if (utf8) {
                writeUtf8String(addedBlob, addedStrings[i])
            } else {
                writeUtf16String(addedBlob, addedStrings[i])
            }
        }
        while ((origStringsBytes.size + addedBlob.size()) % 4 != 0) {
            addedBlob.write(0)
        }
        val addedStringsBytes = addedBlob.toByteArray()

        val newStringCount = origStringCount + addedStrings.size
        val newStringsStart = poolHeaderSize + newStringCount * 4 + origStyleCount * 4
        val combinedStringsSize = origStringsBytes.size + addedStringsBytes.size
        val newStylesStart = if (origStyleCount > 0 && origStylesBytes.isNotEmpty()) {
            newStringsStart + combinedStringsSize
        } else {
            0
        }

        var totalSize = newStringsStart + combinedStringsSize + origStylesBytes.size
        val tailPadding = (4 - (totalSize % 4)) % 4
        totalSize += tailPadding

        val out = ByteArray(totalSize)
        val header = arsc.copyOfRange(poolOffset, poolOffset + poolHeaderSize)
        System.arraycopy(header, 0, out, 0, poolHeaderSize)
        putU16(out, 0, 0x0001)
        putU16(out, 2, poolHeaderSize)
        putU32(out, 4, totalSize)
        putU32(out, 8, newStringCount)
        putU32(out, 12, origStyleCount)
        // Clear SORTED_FLAG (0x01) because appended strings are not globally sorted
        putU32(out, 16, origFlags and 0x01.inv())
        putU32(out, 20, newStringsStart)
        putU32(out, 24, newStylesStart)

        var pos = poolHeaderSize
        System.arraycopy(origStringOffsetsBytes, 0, out, pos, origStringOffsetsBytes.size)
        pos += origStringOffsetsBytes.size
        for (off in addedOffsets) {
            putU32(out, pos, off)
            pos += 4
        }
        if (origStyleOffsetsBytes.isNotEmpty()) {
            System.arraycopy(origStyleOffsetsBytes, 0, out, pos, origStyleOffsetsBytes.size)
            pos += origStyleOffsetsBytes.size
        }
        System.arraycopy(origStringsBytes, 0, out, pos, origStringsBytes.size)
        pos += origStringsBytes.size
        System.arraycopy(addedStringsBytes, 0, out, pos, addedStringsBytes.size)
        pos += addedStringsBytes.size
        if (origStylesBytes.isNotEmpty()) {
            System.arraycopy(origStylesBytes, 0, out, pos, origStylesBytes.size)
        }
        return out
    }

    private fun readStringPoolStrings(data: ByteArray, poolOffset: Int): List<String> {
        if (poolOffset + 28 > data.size || u16(data, poolOffset) != 0x0001) return emptyList()
        val headerSize = u16(data, poolOffset + 2)
        val count = u32(data, poolOffset + 8)
        val flags = u32(data, poolOffset + 16)
        val stringsStart = u32(data, poolOffset + 20)
        val utf8 = (flags and 0x100) != 0
        val offsetsBase = poolOffset + headerSize
        val stringsBase = poolOffset + stringsStart
        val result = ArrayList<String>(count)
        for (i in 0 until count) {
            val offPos = offsetsBase + i * 4
            if (offPos + 4 > data.size) break
            val strOff = u32(data, offPos)
            val absPos = stringsBase + strOff
            if (absPos < 0 || absPos >= data.size) {
                result.add("")
            } else {
                result.add(readString(data, absPos, utf8))
            }
        }
        return result
    }

    private fun readString(data: ByteArray, offset: Int, utf8: Boolean): String {
        if (utf8) {
            var pos = offset
            val first = data[pos++].toInt() and 0xff
            if ((first and 0x80) != 0) pos++
            var byteLen = data[pos++].toInt() and 0xff
            if ((byteLen and 0x80) != 0) {
                byteLen = ((byteLen and 0x7f) shl 8) or (data[pos++].toInt() and 0xff)
            }
            if (pos + byteLen > data.size) return ""
            return String(data, pos, byteLen, Charsets.UTF_8)
        } else {
            var pos = offset
            if (pos + 2 > data.size) return ""
            var charLen = u16(data, pos)
            pos += 2
            if ((charLen and 0x8000) != 0) {
                if (pos + 2 > data.size) return ""
                charLen = ((charLen and 0x7fff) shl 16) or u16(data, pos)
                pos += 2
            }
            val byteLen = charLen * 2
            if (pos + byteLen > data.size) return ""
            return String(data, pos, byteLen, Charsets.UTF_16LE)
        }
    }

    private fun writeUtf8String(out: ByteArrayOutputStream, value: String) {
        val utf8Bytes = value.toByteArray(Charsets.UTF_8)
        writeUtf8Length(out, value.length)
        writeUtf8Length(out, utf8Bytes.size)
        out.write(utf8Bytes)
        out.write(0)
    }

    private fun writeUtf8Length(out: ByteArrayOutputStream, length: Int) {
        if (length >= 0x80) {
            out.write(((length ushr 8) and 0x7f) or 0x80)
            out.write(length and 0xff)
        } else {
            out.write(length)
        }
    }

    private fun writeUtf16String(out: ByteArrayOutputStream, value: String) {
        val len = value.length
        if (len >= 0x8000) {
            val high = ((len ushr 16) and 0x7fff) or 0x8000
            val low = len and 0xffff
            out.write(high and 0xff)
            out.write((high ushr 8) and 0xff)
            out.write(low and 0xff)
            out.write((low ushr 8) and 0xff)
        } else {
            out.write(len and 0xff)
            out.write((len ushr 8) and 0xff)
        }
        out.write(value.toByteArray(Charsets.UTF_16LE))
        out.write(0)
        out.write(0)
    }

    private fun u16(data: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(data, offset, 2).order(little).short.toInt() and 0xffff

    private fun u32(data: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(data, offset, 4).order(little).int

    private fun putU16(data: ByteArray, offset: Int, value: Int) {
        ByteBuffer.wrap(data, offset, 2).order(little).putShort(value.toShort())
    }

    private fun putU32(data: ByteArray, offset: Int, value: Int) {
        ByteBuffer.wrap(data, offset, 4).order(little).putInt(value)
    }
}
