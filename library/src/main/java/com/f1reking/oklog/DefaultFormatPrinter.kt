/*
 * Copyright 2021 F1ReKing.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.f1reking.oklog

import android.text.TextUtils
import com.f1reking.oklog.CharacterHandler.jsonFormat
import com.f1reking.oklog.CharacterHandler.xmlFormat
import com.f1reking.oklog.LogInterceptor.Companion.isJson
import com.f1reking.oklog.LogInterceptor.Companion.isXml
import com.f1reking.oklog.NetLogUtils.debugInfo
import okhttp3.Headers
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.EOFException
import java.io.IOException
import java.nio.charset.Charset

/**
 * Created by F1ReKing
 *
 * 对 OkHttp 的请求和响应信息进行更规范和清晰的打印, 此类为框架默认实现, 以默认格式打印信息, 若觉得默认打印格式
 * 并不能满足自己的需求, 可自行扩展自己理想的打印格式
 */
class DefaultFormatPrinter : FormatPrinter {
    /**
     * 打印网络请求信息, 当网络请求时 {[RequestBody]} 可以解析的情况
     */
    override fun printJsonRequest(request: Request, bodyString: String) {
        val requestBody = LINE_SEPARATOR + BODY_TAG + LINE_SEPARATOR + bodyString
        val tag = getTag(true)
        debugInfo(tag, REQUEST_UP_LINE)
        logLines(tag, arrayOf(URL_TAG + request.url()), false)
        logLines(tag, getRequest(request), true)
        logLines(tag, requestBody.split(LINE_SEPARATOR.toRegex()).toTypedArray(), true)
        debugInfo(tag, END_LINE)
    }

    /**
     * 打印网络请求信息, 当网络请求时 {[RequestBody]} 为 `null` 或不可解析的情况
     */
    override fun printFileRequest(request: Request) {
        val tag = getTag(true)
        debugInfo(tag, REQUEST_UP_LINE)
        logLines(tag, arrayOf(URL_TAG + request.url()), false)
        logLines(tag, getRequest(request), true)
        logLines(tag, OMITTED_REQUEST, true)
        //        logLines(tag,bodyToString(request.body(),request.headers()),true);
        debugInfo(tag, bodyToString(request.body(), request.headers()))
        debugInfo(tag, END_LINE)
    }

    private fun bodyToString(requestBody: RequestBody?, headers: Headers): String {
        if (requestBody != null) {
            if (bodyHasUnknownEncoding(headers)) {
                return "encoded body omitted)"
            } else {
                val buffer = Buffer()
                try {
                    requestBody.writeTo(buffer)
                    val mediaType = requestBody.contentType()
                    if (mediaType != null) {
                        var charset = mediaType.charset(Charset.forName("UTF-8"))
                        if (charset == null) {
                            charset = Charset.forName("UTF-8")
                        }
                        val l = requestBody.contentLength()
                        return if (isProbablyUtf8(buffer)) {
                            (getJsonString(buffer.readString(charset))
                                    + LINE_SEPARATOR
                                    + l
                                    + "-byte body")
                        } else {
                            "binary " + "l" + "-byte body omitted"
                        }
                    }
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        }
        return ""
    }

    private fun getJsonString(msg: String): String {
        var message = ""
        message = try {
            if (msg.startsWith("{")) {
                val jsonObject = JSONObject(msg)
                jsonObject.toString(JSON_INDENT)
            } else if (msg.startsWith("[")) {
                val jsonArray = JSONArray(msg)
                jsonArray.toString(JSON_INDENT)
            } else {
                msg
            }
        } catch (e: JSONException) {
            OMITTED_REQUEST[1]
        }
        return message
    }

    private fun bodyHasUnknownEncoding(headers: Headers): Boolean {
        val contentEncoding = headers["Content-Encoding"] ?: return false
        val s = contentEncoding.toLowerCase()
        return s != "identity" && s != "gzip"
    }

    private fun isProbablyUtf8(buffer: Buffer): Boolean {
        return try {
            val prefix = Buffer()
            val size = buffer.size()
            val byteCount: Long
            //取小的
            byteCount = if (size > 64) {
                64
            } else {
                size
            }
            buffer.copyTo(prefix, 0, byteCount)
            for (i in 0..15) {
                if (prefix.exhausted()) {
                    break
                }
                val codePoint = prefix.readUtf8CodePoint()
                if (Character.isISOControl(codePoint) && !Character.isWhitespace(codePoint)) {
                    return false
                }
            }
            true
        } catch (e: EOFException) {
            false
        }
    }

    /**
     * 打印网络响应信息, 当网络响应时 {[okhttp3.ResponseBody]} 可以解析的情况
     *
     * @param chainMs 服务器响应耗时(单位毫秒)
     * @param isSuccessful 请求是否成功
     * @param code 响应码
     * @param headers 请求头
     * @param contentType 服务器返回数据的数据类型
     * @param bodyString 服务器返回的数据(已解析)
     * @param segments 域名后面的资源地址
     * @param message 响应信息
     * @param responseUrl 请求地址
     */
    override fun printJsonResponse(
        chainMs: Long, isSuccessful: Boolean, code: Int,
        headers: String, contentType: MediaType?,
        bodyString: String?, segments: List<String?>, message: String,
        responseUrl: String
    ) {
        var bodyString = bodyString
        bodyString =
            if (isJson(contentType)) jsonFormat(bodyString!!) else if (isXml(contentType)) xmlFormat(
                bodyString
            ) else bodyString
        val responseBody = LINE_SEPARATOR + BODY_TAG + LINE_SEPARATOR + bodyString
        val tag = getTag(false)
        val urlLine = arrayOf(URL_TAG + responseUrl, N)
        debugInfo(tag, RESPONSE_UP_LINE)
        //方便访问
        debugInfo(tag, "   " + DEFAULT_LINE + responseUrl)
        logLines(tag, getResponse(headers, chainMs, code, isSuccessful, segments, message), true)
        logLines(tag, responseBody.split(LINE_SEPARATOR.toRegex()).toTypedArray(), true)
        debugInfo(tag, END_LINE)
    }

    /**
     * 打印网络响应信息, 当网络响应时 {[okhttp3.ResponseBody]} 为 `null` 或不可解析的情况
     *
     * @param chainMs 服务器响应耗时(单位毫秒)
     * @param isSuccessful 请求是否成功
     * @param code 响应码
     * @param headers 请求头
     * @param segments 域名后面的资源地址
     * @param message 响应信息
     * @param responseUrl 请求地址
     */
    override fun printFileResponse(
        chainMs: Long, isSuccessful: Boolean, code: Int,
        headers: String,
        segments: List<String?>, message: String,
        responseUrl: String
    ) {
        val tag = getTag(false)
        val urlLine = arrayOf(URL_TAG + responseUrl, N)
        debugInfo(tag, RESPONSE_UP_LINE)
        debugInfo(tag, responseUrl)
        logLines(tag, getResponse(headers, chainMs, code, isSuccessful, segments, message), true)
        logLines(tag, OMITTED_RESPONSE, true)
        debugInfo(tag, END_LINE)
    }

    companion object {
        private const val TAG = "ykt-net"
        private val LINE_SEPARATOR = System.getProperty("line.separator")
        private val DOUBLE_SEPARATOR = LINE_SEPARATOR + LINE_SEPARATOR
        private val OMITTED_RESPONSE = arrayOf(LINE_SEPARATOR, "Omitted response body")
        private val OMITTED_REQUEST = arrayOf(LINE_SEPARATOR, "Omitted request body")
        private const val N = "\n"
        private const val T = "\t"
        private const val REQUEST_UP_LINE =
            "   ┌────── Request ────────────────────────────────────────────────────────────────────────"
        private const val END_LINE =
            "   └───────────────────────────────────────────────────────────────────────────────────────"
        private const val RESPONSE_UP_LINE =
            "   ┌────── Response ─────────────────────────────────────────────────────────────────────"
        private const val BODY_TAG = "Body:"
        private const val URL_TAG = "URL: "
        private const val METHOD_TAG = "Method: @"
        private const val HEADERS_TAG = "Headers:"
        private const val STATUS_CODE_TAG = "Status Code: "
        private const val RECEIVED_TAG = "Received in: "
        private const val CORNER_UP = "┌ "
        private const val CORNER_BOTTOM = "└ "
        private const val CENTER_LINE = "├ "
        private const val DEFAULT_LINE = "│ "
        private const val JSON_INDENT = 3
        private fun isEmpty(line: String): Boolean {
            return TextUtils.isEmpty(line) || N == line || T == line || TextUtils.isEmpty(
                line.trim { it <= ' ' })
        }

        /**
         * 对 `lines` 中的信息进行逐行打印
         *
         * @param withLineSize 为 `true` 时, 每行的信息长度不会超过110, 超过则自动换行
         */
        private fun logLines(tag: String, lines: Array<String>, withLineSize: Boolean) {
            for (line in lines) {
                val lineLength = line.length
                val MAX_LONG_SIZE = if (withLineSize) 110 else lineLength
                for (i in 0..lineLength / MAX_LONG_SIZE) {
                    val start = i * MAX_LONG_SIZE
                    var end = (i + 1) * MAX_LONG_SIZE
                    end = if (end > line.length) line.length else end
                    debugInfo(resolveTag(tag), DEFAULT_LINE + line.substring(start, end))
                }
            }
        }

        private val last: ThreadLocal<Int> = object : ThreadLocal<Int>() {
            override fun initialValue(): Int {
                return 0
            }
        }
        private val ARMS = arrayOf("-A-", "-R-", "-M-", "-S-")
        private fun computeKey(): String {
            if (last.get() >= 4) {
                last.set(0)
            }
            val s = ARMS[last.get()]
            last.set(last.get() + 1)
            return s
        }

        /**
         * 此方法是为了解决在 AndroidStudio v3.1 以上 Logcat 输出的日志无法对齐的问题
         *
         *
         * 此问题引起的原因, 据 JessYan 猜测, 可能是因为 AndroidStudio v3.1 以上将极短时间内以相同 tag 输出多次的 log 自动合并为一次输出
         * 导致本来对称的输出日志, 出现不对称的问题
         * AndroidStudio v3.1 此次对输出日志的优化, 不小心使市面上所有具有日志格式化输出功能的日志框架无法正常工作
         * 现在暂时能想到的解决方案有两个: 1. 改变每行的 tag (每行 tag 都加一个可变化的 token) 2. 延迟每行日志打印的间隔时间
         *
         *
         * [.resolveTag] 使用第一种解决方案
         */
        private fun resolveTag(tag: String): String {
            return computeKey() + tag
        }

        private fun getRequest(request: Request): Array<String> {
            val log: String
            val header = request.headers().toString()
            log = METHOD_TAG + request.method() + DOUBLE_SEPARATOR +
                    if (isEmpty(header)) "" else HEADERS_TAG + LINE_SEPARATOR + dotHeaders(header)
            return log.split(LINE_SEPARATOR.toRegex()).toTypedArray()
        }

        private fun getResponse(
            header: String, tookMs: Long, code: Int, isSuccessful: Boolean,
            segments: List<String?>, message: String
        ): Array<String> {
            val log: String
            val segmentString = slashSegments(segments)
            log = ((if (!TextUtils.isEmpty(segmentString)) "$segmentString - " else "")
                    + "is success : "
                    + isSuccessful
                    + " - "
                    + RECEIVED_TAG
                    + tookMs
                    + "ms"
                    + DOUBLE_SEPARATOR
                    + STATUS_CODE_TAG
                    +
                    code
                    + " / "
                    + message
                    + DOUBLE_SEPARATOR
                    + if (isEmpty(header)) "" else HEADERS_TAG + LINE_SEPARATOR +
                    dotHeaders(header))
            return log.split(LINE_SEPARATOR.toRegex()).toTypedArray()
        }

        private fun slashSegments(segments: List<String?>): String {
            val segmentString = StringBuilder()
            for (segment in segments) {
                segmentString.append("/").append(segment)
            }
            return segmentString.toString()
        }

        /**
         * 对 `header` 按规定的格式进行处理
         */
        private fun dotHeaders(header: String): String {
            val headers = header.split(LINE_SEPARATOR.toRegex()).toTypedArray()
            val builder = StringBuilder()
            var tag = "─ "
            if (headers.size > 1) {
                for (i in headers.indices) {
                    tag = if (i == 0) {
                        CORNER_UP
                    } else if (i == headers.size - 1) {
                        CORNER_BOTTOM
                    } else {
                        CENTER_LINE
                    }
                    builder.append(tag).append(headers[i]).append("\n")
                }
            } else {
                for (item in headers) {
                    builder.append(tag).append(item).append("\n")
                }
            }
            return builder.toString()
        }

        private fun getTag(isRequest: Boolean): String {
            return if (isRequest) {
                TAG + "-Request"
            } else {
                TAG + "-Response"
            }
        }
    }
}