package com.ketan.slam

/**
 * Sparse 2D occupancy grid storage in fixed-size chunks. Replaces
 * `ConcurrentHashMap<GridCell, V>` for the three per-cell fields in
 * [MapBuilder] (byte grid, log-odds float, observation-count int).
 *
 * Why chunks. A HashMap with one entry per cell pays:
 *   - ~48 bytes per entry (Node object + boxed Byte/Float + GridCell key),
 *   - a hash probe on every get/put,
 *   - node allocation + GC pressure when the user keeps walking.
 *
 * A chunk groups 64×64 cells into a single dense primitive array (4 KB for
 * Byte, 16 KB for Float/Int) that's allocated lazily on first write. Lookups
 * become one hashmap probe (chunk key, amortized) plus an array index. Over
 * a long session in a campus hallway this cuts per-cell memory by roughly
 * 8× and iteration cost by an order of magnitude.
 *
 * The classes here implement the same MutableMap<GridCell, V> surface the
 * existing MapBuilder callers use, so there are no call-site changes. Key
 * and value iteration allocate on demand — acceptable because the hot
 * occupancy math inside MapBuilder already uses the raw
 * [ChunkedByteGrid.forEachCell] / [ChunkedFloatGrid.forEachCell] accessors
 * which skip allocation.
 *
 * Thread safety. The underlying chunk map uses java.util.concurrent so reads
 * are safe. Within a chunk, reads and writes are plain array ops — callers
 * that synchronously mutate (MapBuilder's @Synchronized methods) keep that
 * guarantee. Concurrent reads during writes may see stale cell values, which
 * matches the previous ConcurrentHashMap behaviour.
 */

private const val CHUNK_BITS = 6
private const val CHUNK_SIZE = 1 shl CHUNK_BITS  // 64
private const val CHUNK_MASK = CHUNK_SIZE - 1     // 63
private const val CELLS_PER_CHUNK = CHUNK_SIZE * CHUNK_SIZE  // 4096

/** Pack (chunkX, chunkZ) into a single Long for the outer hashmap key. */
private fun chunkKey(cx: Int, cz: Int): Long =
    (cx.toLong() shl 32) or (cz.toLong() and 0xFFFFFFFFL)
private fun unpackCx(key: Long): Int = (key shr 32).toInt()
private fun unpackCz(key: Long): Int = key.toInt()

/** In-chunk index from global (x, z). */
private fun localIndex(x: Int, z: Int): Int =
    ((z and CHUNK_MASK) shl CHUNK_BITS) or (x and CHUNK_MASK)

/** Chunk-coordinate for a global cell coord. Uses arithmetic shift to
 *  handle negative coordinates correctly (floor division, not truncation). */
private fun toChunk(v: Int): Int = v shr CHUNK_BITS

// ─────────────────────────────────────────────────────────────────────────────
// Byte-valued grid (MapBuilder.grid)
// ─────────────────────────────────────────────────────────────────────────────

/** Sentinel for "no value" in the byte grid. Cell types are 0..4, so 0xFF
 *  (-1 as signed byte) is safe as the absent marker. */
private const val BYTE_ABSENT: Byte = -1

class ChunkedByteGrid : MutableMap<GridCell, Byte> {

    /** Each chunk: ByteArray(4096) initialised to [BYTE_ABSENT]. */
    private val chunks = java.util.concurrent.ConcurrentHashMap<Long, ByteArray>()
    @Volatile private var cachedSize = 0

    private fun chunkOrNull(cx: Int, cz: Int): ByteArray? = chunks[chunkKey(cx, cz)]

    private fun chunkOrAlloc(cx: Int, cz: Int): ByteArray =
        chunks.getOrPut(chunkKey(cx, cz)) { ByteArray(CELLS_PER_CHUNK) { BYTE_ABSENT } }

    // ── Raw (allocation-free) accessors ────────────────────────────────────

    fun getByte(x: Int, z: Int): Byte {
        val c = chunkOrNull(toChunk(x), toChunk(z)) ?: return BYTE_ABSENT
        return c[localIndex(x, z)]
    }

    fun contains(x: Int, z: Int): Boolean = getByte(x, z) != BYTE_ABSENT

    fun putByte(x: Int, z: Int, v: Byte) {
        val c = chunkOrAlloc(toChunk(x), toChunk(z))
        val idx = localIndex(x, z)
        val prev = c[idx]
        c[idx] = v
        if (prev == BYTE_ABSENT) cachedSize++
    }

    fun removeAt(x: Int, z: Int): Boolean {
        val c = chunkOrNull(toChunk(x), toChunk(z)) ?: return false
        val idx = localIndex(x, z)
        if (c[idx] == BYTE_ABSENT) return false
        c[idx] = BYTE_ABSENT
        cachedSize--
        return true
    }

    /** Dense iteration without per-cell GridCell allocation. Callback
     *  receives the global (x, z) and the byte value. */
    inline fun forEachCell(action: (Int, Int, Byte) -> Unit) {
        for ((key, arr) in chunks) {
            val baseX = unpackCx(key) shl CHUNK_BITS
            val baseZ = unpackCz(key) shl CHUNK_BITS
            var i = 0
            var z = 0
            while (z < CHUNK_SIZE) {
                var x = 0
                while (x < CHUNK_SIZE) {
                    val v = arr[i]
                    if (v != BYTE_ABSENT) action(baseX + x, baseZ + z, v)
                    i++
                    x++
                }
                z++
            }
        }
    }

    // ── MutableMap surface ─────────────────────────────────────────────────

    override val size: Int get() = cachedSize
    override fun isEmpty(): Boolean = cachedSize == 0

    override fun containsKey(key: GridCell): Boolean = contains(key.x, key.z)
    override fun containsValue(value: Byte): Boolean {
        forEachCell { _, _, v -> if (v == value) return true }
        return false
    }
    override operator fun get(key: GridCell): Byte? {
        val v = getByte(key.x, key.z)
        return if (v == BYTE_ABSENT) null else v
    }
    override fun put(key: GridCell, value: Byte): Byte? {
        val prev = getByte(key.x, key.z)
        putByte(key.x, key.z, value)
        return if (prev == BYTE_ABSENT) null else prev
    }
    override fun remove(key: GridCell): Byte? {
        val prev = getByte(key.x, key.z)
        if (prev == BYTE_ABSENT) return null
        removeAt(key.x, key.z)
        return prev
    }
    override fun putAll(from: Map<out GridCell, Byte>) {
        for ((k, v) in from) put(k, v)
    }
    override fun clear() { chunks.clear(); cachedSize = 0 }

    override val keys: MutableSet<GridCell>
        get() {
            val out = HashSet<GridCell>(cachedSize.coerceAtLeast(16))
            forEachCell { x, z, _ -> out.add(GridCell(x, z)) }
            return out
        }
    override val values: MutableCollection<Byte>
        get() {
            val out = ArrayList<Byte>(cachedSize.coerceAtLeast(16))
            forEachCell { _, _, v -> out.add(v) }
            return out
        }
    override val entries: MutableSet<MutableMap.MutableEntry<GridCell, Byte>>
        get() {
            val out = HashSet<MutableMap.MutableEntry<GridCell, Byte>>(cachedSize.coerceAtLeast(16))
            forEachCell { x, z, v -> out.add(SimpleEntry(GridCell(x, z), v)) }
            return out
        }
}

// ─────────────────────────────────────────────────────────────────────────────
// Float-valued grid (MapBuilder.logOdds)
// ─────────────────────────────────────────────────────────────────────────────
//
// Unlike bytes we can't use a sentinel value that a user can't produce, so
// each chunk carries a presence bitmap (64 longs × 64 bits = 4096 bits).

class ChunkedFloatGrid : MutableMap<GridCell, Float> {

    /** Parallel arrays per chunk: values + presence bitmap. */
    private class Chunk {
        val data = FloatArray(CELLS_PER_CHUNK)
        val present = LongArray(CELLS_PER_CHUNK / 64)  // 64 longs = 4096 bits
    }

    private val chunks = java.util.concurrent.ConcurrentHashMap<Long, Chunk>()
    @Volatile private var cachedSize = 0

    private fun chunkOrNull(cx: Int, cz: Int): Chunk? = chunks[chunkKey(cx, cz)]
    private fun chunkOrAlloc(cx: Int, cz: Int): Chunk =
        chunks.getOrPut(chunkKey(cx, cz)) { Chunk() }

    private fun isPresent(c: Chunk, idx: Int): Boolean {
        val word = idx ushr 6
        val bit = idx and 63
        return (c.present[word] and (1L shl bit)) != 0L
    }
    private fun markPresent(c: Chunk, idx: Int): Boolean {
        val word = idx ushr 6
        val bit = idx and 63
        val mask = 1L shl bit
        if ((c.present[word] and mask) != 0L) return false
        c.present[word] = c.present[word] or mask
        return true
    }
    private fun markAbsent(c: Chunk, idx: Int): Boolean {
        val word = idx ushr 6
        val bit = idx and 63
        val mask = 1L shl bit
        if ((c.present[word] and mask) == 0L) return false
        c.present[word] = c.present[word] and mask.inv()
        return true
    }

    // ── Raw accessors ──────────────────────────────────────────────────────

    fun getOrDefault(x: Int, z: Int, default: Float): Float {
        val c = chunkOrNull(toChunk(x), toChunk(z)) ?: return default
        val idx = localIndex(x, z)
        return if (isPresent(c, idx)) c.data[idx] else default
    }

    fun putFloat(x: Int, z: Int, v: Float) {
        val c = chunkOrAlloc(toChunk(x), toChunk(z))
        val idx = localIndex(x, z)
        c.data[idx] = v
        if (markPresent(c, idx)) cachedSize++
    }

    fun removeAt(x: Int, z: Int): Boolean {
        val c = chunkOrNull(toChunk(x), toChunk(z)) ?: return false
        val idx = localIndex(x, z)
        if (!markAbsent(c, idx)) return false
        c.data[idx] = 0f
        cachedSize--
        return true
    }

    /** Dense iteration that skips absent cells via the presence bitmap. */
    inline fun forEachCell(action: (Int, Int, Float) -> Unit) {
        for ((key, chunk) in chunks) {
            val baseX = unpackCx(key) shl CHUNK_BITS
            val baseZ = unpackCz(key) shl CHUNK_BITS
            val present = chunk.present
            val data = chunk.data
            var w = 0
            while (w < present.size) {
                var bits = present[w]
                while (bits != 0L) {
                    val bit = java.lang.Long.numberOfTrailingZeros(bits)
                    val idx = (w shl 6) or bit
                    val x = idx and CHUNK_MASK
                    val z = idx ushr CHUNK_BITS
                    action(baseX + x, baseZ + z, data[idx])
                    bits = bits and (bits - 1L)  // clear low set bit
                }
                w++
            }
        }
    }

    // ── MutableMap surface ─────────────────────────────────────────────────

    override val size: Int get() = cachedSize
    override fun isEmpty(): Boolean = cachedSize == 0

    override fun containsKey(key: GridCell): Boolean {
        val c = chunkOrNull(toChunk(key.x), toChunk(key.z)) ?: return false
        return isPresent(c, localIndex(key.x, key.z))
    }
    override fun containsValue(value: Float): Boolean {
        forEachCell { _, _, v -> if (v == value) return true }
        return false
    }
    override operator fun get(key: GridCell): Float? {
        val c = chunkOrNull(toChunk(key.x), toChunk(key.z)) ?: return null
        val idx = localIndex(key.x, key.z)
        return if (isPresent(c, idx)) c.data[idx] else null
    }
    override fun put(key: GridCell, value: Float): Float? {
        val prev = get(key)
        putFloat(key.x, key.z, value)
        return prev
    }
    override fun remove(key: GridCell): Float? {
        val prev = get(key) ?: return null
        removeAt(key.x, key.z)
        return prev
    }
    override fun putAll(from: Map<out GridCell, Float>) {
        for ((k, v) in from) put(k, v)
    }
    override fun clear() { chunks.clear(); cachedSize = 0 }

    override val keys: MutableSet<GridCell>
        get() {
            val out = HashSet<GridCell>(cachedSize.coerceAtLeast(16))
            forEachCell { x, z, _ -> out.add(GridCell(x, z)) }
            return out
        }
    override val values: MutableCollection<Float>
        get() {
            val out = ArrayList<Float>(cachedSize.coerceAtLeast(16))
            forEachCell { _, _, v -> out.add(v) }
            return out
        }
    override val entries: MutableSet<MutableMap.MutableEntry<GridCell, Float>>
        get() {
            val out = HashSet<MutableMap.MutableEntry<GridCell, Float>>(cachedSize.coerceAtLeast(16))
            forEachCell { x, z, v -> out.add(SimpleEntry(GridCell(x, z), v)) }
            return out
        }
}

// ─────────────────────────────────────────────────────────────────────────────
// Int-valued grid (MapBuilder.observationCounts)
// ─────────────────────────────────────────────────────────────────────────────

class ChunkedIntGrid : MutableMap<GridCell, Int> {

    private class Chunk {
        val data = IntArray(CELLS_PER_CHUNK)
        val present = LongArray(CELLS_PER_CHUNK / 64)
    }

    private val chunks = java.util.concurrent.ConcurrentHashMap<Long, Chunk>()
    @Volatile private var cachedSize = 0

    private fun chunkOrNull(cx: Int, cz: Int): Chunk? = chunks[chunkKey(cx, cz)]
    private fun chunkOrAlloc(cx: Int, cz: Int): Chunk =
        chunks.getOrPut(chunkKey(cx, cz)) { Chunk() }

    private fun isPresent(c: Chunk, idx: Int): Boolean {
        val word = idx ushr 6; val bit = idx and 63
        return (c.present[word] and (1L shl bit)) != 0L
    }
    private fun markPresent(c: Chunk, idx: Int): Boolean {
        val word = idx ushr 6; val bit = idx and 63
        val mask = 1L shl bit
        if ((c.present[word] and mask) != 0L) return false
        c.present[word] = c.present[word] or mask
        return true
    }
    private fun markAbsent(c: Chunk, idx: Int): Boolean {
        val word = idx ushr 6; val bit = idx and 63
        val mask = 1L shl bit
        if ((c.present[word] and mask) == 0L) return false
        c.present[word] = c.present[word] and mask.inv()
        return true
    }

    fun getOrDefault(x: Int, z: Int, default: Int): Int {
        val c = chunkOrNull(toChunk(x), toChunk(z)) ?: return default
        val idx = localIndex(x, z)
        return if (isPresent(c, idx)) c.data[idx] else default
    }

    fun putInt(x: Int, z: Int, v: Int) {
        val c = chunkOrAlloc(toChunk(x), toChunk(z))
        val idx = localIndex(x, z)
        c.data[idx] = v
        if (markPresent(c, idx)) cachedSize++
    }

    fun removeAt(x: Int, z: Int): Boolean {
        val c = chunkOrNull(toChunk(x), toChunk(z)) ?: return false
        val idx = localIndex(x, z)
        if (!markAbsent(c, idx)) return false
        c.data[idx] = 0
        cachedSize--
        return true
    }

    override val size: Int get() = cachedSize
    override fun isEmpty(): Boolean = cachedSize == 0
    override fun containsKey(key: GridCell): Boolean {
        val c = chunkOrNull(toChunk(key.x), toChunk(key.z)) ?: return false
        return isPresent(c, localIndex(key.x, key.z))
    }
    override fun containsValue(value: Int): Boolean {
        for ((_, chunk) in chunks) {
            val present = chunk.present
            val data = chunk.data
            var w = 0
            while (w < present.size) {
                var bits = present[w]
                while (bits != 0L) {
                    val bit = java.lang.Long.numberOfTrailingZeros(bits)
                    val idx = (w shl 6) or bit
                    if (data[idx] == value) return true
                    bits = bits and (bits - 1L)
                }
                w++
            }
        }
        return false
    }
    override operator fun get(key: GridCell): Int? {
        val c = chunkOrNull(toChunk(key.x), toChunk(key.z)) ?: return null
        val idx = localIndex(key.x, key.z)
        return if (isPresent(c, idx)) c.data[idx] else null
    }
    override fun put(key: GridCell, value: Int): Int? {
        val prev = get(key)
        putInt(key.x, key.z, value)
        return prev
    }
    override fun remove(key: GridCell): Int? {
        val prev = get(key) ?: return null
        removeAt(key.x, key.z)
        return prev
    }
    override fun putAll(from: Map<out GridCell, Int>) {
        for ((k, v) in from) put(k, v)
    }
    override fun clear() { chunks.clear(); cachedSize = 0 }

    override val keys: MutableSet<GridCell>
        get() {
            val out = HashSet<GridCell>(cachedSize.coerceAtLeast(16))
            for ((key, chunk) in chunks) {
                val baseX = unpackCx(key) shl CHUNK_BITS
                val baseZ = unpackCz(key) shl CHUNK_BITS
                val present = chunk.present
                var w = 0
                while (w < present.size) {
                    var bits = present[w]
                    while (bits != 0L) {
                        val bit = java.lang.Long.numberOfTrailingZeros(bits)
                        val idx = (w shl 6) or bit
                        out.add(GridCell(baseX + (idx and CHUNK_MASK), baseZ + (idx ushr CHUNK_BITS)))
                        bits = bits and (bits - 1L)
                    }
                    w++
                }
            }
            return out
        }
    override val values: MutableCollection<Int>
        get() {
            val out = ArrayList<Int>(cachedSize.coerceAtLeast(16))
            for ((_, chunk) in chunks) {
                val present = chunk.present
                val data = chunk.data
                var w = 0
                while (w < present.size) {
                    var bits = present[w]
                    while (bits != 0L) {
                        val bit = java.lang.Long.numberOfTrailingZeros(bits)
                        val idx = (w shl 6) or bit
                        out.add(data[idx])
                        bits = bits and (bits - 1L)
                    }
                    w++
                }
            }
            return out
        }
    override val entries: MutableSet<MutableMap.MutableEntry<GridCell, Int>>
        get() {
            val out = HashSet<MutableMap.MutableEntry<GridCell, Int>>(cachedSize.coerceAtLeast(16))
            for ((key, chunk) in chunks) {
                val baseX = unpackCx(key) shl CHUNK_BITS
                val baseZ = unpackCz(key) shl CHUNK_BITS
                val present = chunk.present
                val data = chunk.data
                var w = 0
                while (w < present.size) {
                    var bits = present[w]
                    while (bits != 0L) {
                        val bit = java.lang.Long.numberOfTrailingZeros(bits)
                        val idx = (w shl 6) or bit
                        val cell = GridCell(baseX + (idx and CHUNK_MASK), baseZ + (idx ushr CHUNK_BITS))
                        out.add(SimpleEntry(cell, data[idx]))
                        bits = bits and (bits - 1L)
                    }
                    w++
                }
            }
            return out
        }
}

// ─────────────────────────────────────────────────────────────────────────────
// Shared helpers
// ─────────────────────────────────────────────────────────────────────────────

private class SimpleEntry<V>(
    override val key: GridCell,
    private var v: V
) : MutableMap.MutableEntry<GridCell, V> {
    override val value: V get() = v
    override fun setValue(newValue: V): V { val old = v; v = newValue; return old }
    override fun hashCode(): Int = key.hashCode() xor (v?.hashCode() ?: 0)
    override fun equals(other: Any?): Boolean =
        other is Map.Entry<*, *> && other.key == key && other.value == v
    override fun toString(): String = "$key=$v"
}
