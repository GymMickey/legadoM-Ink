package io.legado.app.help

import io.legado.app.data.entities.BaseSource
import io.legado.app.exception.ConcurrentException
import io.legado.app.model.analyzeRule.AnalyzeUrl.ConcurrentRecord
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

class ConcurrentRateLimiter(source: BaseSource?) {

    companion object {
        val concurrentRecordMap = ConcurrentHashMap<String, ConcurrentRecord>()

        /**
         * 更新并发率
         */
        fun updateConcurrentRate(key: String, concurrentRate: String) {
            concurrentRecordMap.compute(key) { _, record ->
                try {
                    val rateIndex = concurrentRate.indexOf("/")
                    when {
                        rateIndex > 0 -> {
                            val accessLimit = concurrentRate.take(rateIndex).toInt()
                            val interval = concurrentRate.substring(rateIndex + 1).toInt()
                            if (accessLimit <= 0 || interval <= 0) throw NumberFormatException()
                            ConcurrentRecord(
                                record?.time ?: System.currentTimeMillis(),
                                accessLimit,
                                interval,
                                record?.frequency ?: 0
                            )
                        }
                        concurrentRate.toInt() > 0 -> {
                            ConcurrentRecord(
                                record?.time ?: System.currentTimeMillis(),
                                1,
                                concurrentRate.toInt(),
                                record?.frequency ?: 0
                            )
                        }
                        else -> record
                    }
                } catch (_: NumberFormatException) {
                    record
                }
            }
        }

        /**
         * 取两个并发率中限制更严格（吞吐量更低）者
         * null/空/"0" 表示无限制，不参与比较
         */
        fun effectiveRate(rate1: String?, rate2: String?): String? {
            val t1 = throughput(rate1)
            val t2 = throughput(rate2)
            return if (t1 <= t2) rate1 else rate2
        }

        /**
         * 校验并发率格式是否合法
         * 空字符串和 null 视为合法（表示不限制）
         * 合法格式：纯数字 "1500" 或 "次数/毫秒" 如 "20/60000"
         */
        fun isValidRate(rate: String?): Boolean {
            if (rate.isNullOrBlank()) return true
            val regex = Regex("^(\\d+)(/(\\d+))?$")
            val match = regex.matchEntire(rate) ?: return false
            val num = match.groupValues[1].toInt()
            if (num <= 0) return false
            val denom = match.groupValues[3]
            return denom.isEmpty() || denom.toInt() > 0
        }

        /**
         * 计算并发率的吞吐量（请求/秒），用于比较限制严格程度
         * null/空/"0" 返回正无穷（无限制，不参与比较）
         * "1500" → 1000/1500 ≈ 0.667 req/s
         * "20/60000" → 20*1000/60000 ≈ 0.333 req/s
         */
        private fun throughput(rate: String?): Double {
            if (rate.isNullOrBlank() || rate == "0") return Double.POSITIVE_INFINITY
            return try {
                val idx = rate.indexOf("/")
                if (idx > 0) {
                    val limit = rate.take(idx).toDouble()
                    val ms = rate.substring(idx + 1).toDouble()
                    if (limit <= 0 || ms <= 0) Double.POSITIVE_INFINITY
                    else limit * 1000.0 / ms
                } else {
                    val ms = rate.toDouble()
                    if (ms <= 0) Double.POSITIVE_INFINITY
                    else 1000.0 / ms
                }
            } catch (_: NumberFormatException) {
                Double.POSITIVE_INFINITY
            }
        }

        /**
         * 根据并发率字符串构建 ConcurrentRecord
         */
        private fun buildRecord(rate: String): ConcurrentRecord {
            val rateIndex = rate.indexOf("/")
            if (rateIndex > 0) {
                val accessLimit = rate.take(rateIndex).toIntOrNull() ?: 1
                val interval = rate.substring(rateIndex + 1).toIntOrNull() ?: 0
                return ConcurrentRecord(System.currentTimeMillis(), accessLimit, interval, 1)
            }
            return ConcurrentRecord(System.currentTimeMillis(), 1, rate.toIntOrNull() ?: 0, 1)
        }

        /**
         * 将 ConcurrentRecord 还原为并发率字符串
         */
        private fun recordToRate(record: ConcurrentRecord): String {
            return if (record.accessLimit > 1) "${record.accessLimit}/${record.interval}"
            else record.interval.toString()
        }
    }

    private val source: BaseSource? = source
    private val key = source?.getKey()
    /**
     * 开始访问,并发判断
     * 每次调用实时读取 source?.concurrentRate，确保外部对 BookSource 对象并发率的修改即时生效
     * 若 putConcurrent() 修改了记录，取 source.concurrentRate 与 putConcurrent 值中限制更严格者
     */
    @Throws(ConcurrentException::class)
    private fun fetchStart(): ConcurrentRecord? {
        val sourceRate = source?.concurrentRate
        if (sourceRate.isNullOrEmpty() || sourceRate == "0") {
            return null
        }
        val key = key ?: return null
        var isNewRecord = false
        val fetchRecord = concurrentRecordMap.compute(key) { _, record ->
            if (record == null) {
                isNewRecord = true
                return@compute buildRecord(sourceRate)
            }
            val recordRate = recordToRate(record)
            if (recordRate != sourceRate) {
                val effective = effectiveRate(sourceRate, recordRate)
                if (effective != recordRate) {
                    isNewRecord = true
                    effective?.let { buildRecord(it) } ?: record
                } else {
                    record
                }
            } else {
                record
            }
        } ?: return null
        if (isNewRecord) return fetchRecord
        val waitTime: Long = synchronized(fetchRecord) {
            //并发控制为 次数/毫秒 , 非并发实际为1/毫秒
            val nextTime = fetchRecord.time + fetchRecord.interval.toLong()
            val nowTime = System.currentTimeMillis()
            if (nowTime >= nextTime) {
                //已经过了限制时间,重置开始时间
                fetchRecord.time = nowTime
                fetchRecord.frequency = 1
                return@synchronized 0
            }
            if (fetchRecord.frequency < fetchRecord.accessLimit) {
                fetchRecord.frequency ++
                return@synchronized 0
            } else {
                return@synchronized nextTime - nowTime
            }
        }
        if (waitTime > 0) {
            throw ConcurrentException(
                "根据并发率还需等待${waitTime}毫秒才可以访问",
                waitTime = waitTime
            )
        }
        return fetchRecord
    }

    /**
     * 获取并发记录，若处于并发限制状态下则会等待
     */
    suspend fun getConcurrentRecord(): ConcurrentRecord? {
        while (true) {
            try {
                return fetchStart()
            } catch (e: ConcurrentException) {
                delay(e.waitTime)
            }
        }
    }

    fun getConcurrentRecordBlocking(): ConcurrentRecord? {
        while (true) {
            try {
                return fetchStart()
            } catch (e: ConcurrentException) {
                Thread.sleep(e.waitTime)
            }
        }
    }

    suspend inline fun <T> withLimit(block: () -> T): T {
        getConcurrentRecord()
        return block()
    }

    inline fun <T> withLimitBlocking(block: () -> T): T {
        getConcurrentRecordBlocking()
        return block()
    }

}
