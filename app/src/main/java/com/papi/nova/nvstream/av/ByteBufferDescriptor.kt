package com.papi.nova.nvstream.av

class ByteBufferDescriptor {
    @JvmField var data: ByteArray
    @JvmField var offset: Int
    @JvmField var length: Int
    @JvmField var nextDescriptor: ByteBufferDescriptor? = null

    constructor(data: ByteArray, offset: Int, length: Int) {
        this.data = data
        this.offset = offset
        this.length = length
    }

    constructor(desc: ByteBufferDescriptor) {
        data = desc.data
        offset = desc.offset
        length = desc.length
    }

    fun reinitialize(data: ByteArray, offset: Int, length: Int) {
        this.data = data
        this.offset = offset
        this.length = length
        nextDescriptor = null
    }

    fun print() {
        print(offset, length)
    }

    fun print(length: Int) {
        print(offset, length)
    }

    fun print(offset: Int, length: Int) {
        var i = offset
        while (i < offset + length) {
            if (i + 8 <= offset + length) {
                System.out.printf(
                    "%x: %02x %02x %02x %02x %02x %02x %02x %02x\n",
                    i,
                    data[i],
                    data[i + 1],
                    data[i + 2],
                    data[i + 3],
                    data[i + 4],
                    data[i + 5],
                    data[i + 6],
                    data[i + 7],
                )
                i += 8
            } else {
                System.out.printf("%x: %02x \n", i, data[i])
                i++
            }
        }
        kotlin.io.println()
    }
}
