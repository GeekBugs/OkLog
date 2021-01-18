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

import com.f1reking.oklog.CharacterHandler.jsonFormat
import com.f1reking.oklog.UrlEncoderUtils.hasUrlEncoded
import okhttp3.*
import okio.Buffer
import java.io.IOException
import java.io.UnsupportedEncodingException
import java.net.URLDecoder
import java.nio.charset.Charset
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Created by F1ReKing
 *
 * 解析框架中的网络请求和响应结果,并以日志形式输出
 */
class LogInterceptor(level: Level) : Interceptor {
    private var mPrinter: FormatPrinter = DefaultFormatPrinter()
    var printLevel: Level = level

    enum class Level {
        /**
         * 不打印log
         */
        NONE,

        /**
         * 只打印请求信息
         */
        REQUEST,

        /**
         * 只打印响应信息
         */
        RESPONSE,

        /**
         * 所有数据全部打印
         */
        ALL
    }

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val originalResponse = chain.proceed(request)
        val logRequest =
            printLevel == Level.ALL || printLevel != Level.NONE && printLevel == Level.REQUEST
        val logResponse =
            printLevel == Level.ALL || printLevel != Level.NONE && printLevel == Level.RESPONSE
        val t1 = if (logResponse) System.nanoTime() else 0
        val responseBody = originalResponse.body()
        val t2 = if (logResponse) System.nanoTime() else 0
        var bodyString: String? = null
        if (responseBody != null && isParseable(responseBody.contentType())) {
            bodyString = printResult(request, originalResponse, logResponse)
        }
        val finalBodyString = bodyString
        val run = Runnable {
            if (logRequest) {
                //打印请求信息
                if (request.body() != null && isParseable(request.body()!!.contentType())) {
                    try {
                        mPrinter.printJsonRequest(request, parseParams(request))
                    } catch (e: UnsupportedEncodingException) {
                        e.printStackTrace()
                    }
                } else {
                    mPrinter.printFileRequest(request)
                }
            }

            //打印响应结果
            if (logResponse) {
                val segmentList = request.url().encodedPathSegments()
                val header = originalResponse.headers().toString()
                val code = originalResponse.code()
                val isSuccessful = originalResponse.isSuccessful
                val message = originalResponse.message()
                val url = originalResponse.request().url().toString()
                if (responseBody != null && isParseable(responseBody.contentType())) {
                    mPrinter.printJsonResponse(
                        TimeUnit.NANOSECONDS.toMillis(t2 - t1),
                        isSuccessful,
                        code,
                        header,
                        responseBody.contentType(),
                        finalBodyString,
                        segmentList,
                        message,
                        url
                    )
                } else {
                    mPrinter.printFileResponse(
                        TimeUnit.NANOSECONDS.toMillis(t2 - t1),
                        isSuccessful, code, header, segmentList, message, url
                    )
                }
            }
        }
        postTask(run)
        return originalResponse
    }

    /**
     * 打印响应结果
     *
     * @param request     [Request]
     * @param response    [Response]
     * @param logResponse 是否打印响应结果
     * @return 解析后的响应结果
     * @throws IOException
     */
    @Throws(IOException::class)
    private fun printResult(request: Request, response: Response, logResponse: Boolean): String? {
        return try {
            //读取服务器返回的结果
            val responseBody = response.newBuilder().build().body()
            val source = responseBody!!.source()
            source.request(Long.MAX_VALUE) // Buffer the entire body.
            val buffer = source.buffer()

            //获取content的压缩类型
            val encoding = response
                .headers()["Content-Encoding"]
            val clone = buffer.clone()

            //解析response content
            parseContent(responseBody, encoding, clone)
        } catch (e: IOException) {
            e.printStackTrace()
            "{\"error\": \"" + e.message + "\"}"
        }
    }

    /**
     * 解析服务器响应的内容
     *
     * @param responseBody [ResponseBody]
     * @param encoding     编码类型
     * @param clone        克隆后的服务器响应内容
     * @return 解析后的响应结果
     */
    private fun parseContent(
        responseBody: ResponseBody?,
        encoding: String?,
        clone: Buffer
    ): String {
        var charset = Charset.forName("UTF-8")
        val contentType = responseBody!!.contentType()
        if (contentType != null) {
            charset = contentType.charset(charset)
        }
        return if (encoding != null && encoding.equals(
                "gzip",
                ignoreCase = true
            )
        ) { //content 使用 gzip 压缩
            ZipHelper.decompressForGzip(
                clone.readByteArray(),
                convertCharset(charset)
            )!! //解压
        } else if (encoding != null && encoding.equals(
                "zlib",
                ignoreCase = true
            )
        ) { //content 使用 zlib 压缩
            ZipHelper.decompressToStringForZlib(
                clone.readByteArray(),
                convertCharset(charset)
            )!! //解压
        } else { //content 没有被压缩, 或者使用其他未知压缩方式
            clone.readString(charset)
        }
    }

    companion object {
        /**
         * 解析请求服务器的请求参数
         *
         * @param request [Request]
         * @return 解析后的请求信息
         * @throws UnsupportedEncodingException
         */
        @Throws(UnsupportedEncodingException::class)
        fun parseParams(request: Request): String {
            return try {
                val body = request.newBuilder().build().body() ?: return ""
                val requestbuffer = Buffer()
                body.writeTo(requestbuffer)
                var charset = Charset.forName("UTF-8")
                val contentType = body.contentType()
                if (contentType != null) {
                    charset = contentType.charset(charset)
                }
                var json = requestbuffer.readString(charset)
                if (hasUrlEncoded(json!!)) {
                    json = URLDecoder.decode(json, convertCharset(charset))
                }
                jsonFormat(json!!)
            } catch (e: IOException) {
                e.printStackTrace()
                "{\"error\": \"" + e.message + "\"}"
            }
        }

        /**
         * 是否可以解析
         *
         * @param mediaType [MediaType]
         * @return `true` 为可以解析
         */
        fun isParseable(mediaType: MediaType?): Boolean {
            return if (mediaType?.type() == null) false else isText(mediaType) || isPlain(mediaType) || isJson(
                mediaType
            ) || isForm(mediaType) || isHtml(mediaType) || isXml(mediaType)
        }

        fun isText(mediaType: MediaType?): Boolean {
            return if (mediaType?.type() == null) false else mediaType.type() == "text"
        }

        fun isPlain(mediaType: MediaType?): Boolean {
            return if (mediaType?.subtype() == null) false else mediaType.subtype()
                .toLowerCase().contains("plain")
        }

        @JvmStatic
        fun isJson(mediaType: MediaType?): Boolean {
            return if (mediaType?.subtype() == null) false else mediaType.subtype()
                .toLowerCase().contains("json")
        }

        @JvmStatic
        fun isXml(mediaType: MediaType?): Boolean {
            return if (mediaType?.subtype() == null) false else mediaType.subtype()
                .toLowerCase().contains("xml")
        }

        fun isHtml(mediaType: MediaType?): Boolean {
            return if (mediaType?.subtype() == null) false else mediaType.subtype()
                .toLowerCase().contains("html")
        }

        fun isForm(mediaType: MediaType?): Boolean {
            return if (mediaType?.subtype() == null) false else mediaType.subtype()
                .toLowerCase().contains("x-www-form-urlencoded")
        }

        fun convertCharset(charset: Charset?): String {
            val s = charset.toString()
            val i = s.indexOf("[")
            return if (i == -1) {
                s
            } else s.substring(i + 1, s.length - 1)
        }

        /**
         * 日志打印线程池
         */
        private var sLogExecutor: ExecutorService? = null

        @Synchronized
        fun postTask(run: Runnable?) {
            if (sLogExecutor == null) {
                sLogExecutor =
                    ThreadPoolExecutor(0, 1, 60, TimeUnit.SECONDS, LinkedBlockingDeque())
            }
            sLogExecutor!!.execute(run)
        }
    }

}