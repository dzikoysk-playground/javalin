/*
 * Javalin - https://javalin.io
 * Copyright 2017 David Åse
 * Licensed under Apache 2.0: https://github.com/tipsy/javalin/blob/master/LICENSE
 */

package io.javalin

import io.javalin.http.ContentType
import io.javalin.testing.TestUtil
import io.javalin.testing.UploadInfo
import io.javalin.testing.fasterJacksonMapper
import jakarta.servlet.MultipartConfigElement
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

class TestMultipartForms {

    private val LF = "\n"
    private val CRLF = "\r\n"
    private val TEXT_FILE_CONTENT_LF = "This is my content." + LF + "It's two lines." + LF
    private val TEXT_FILE_CONTENT_CRLF = "This is my content." + CRLF + "It's two lines." + CRLF

    // Using OkHttp because Unirest doesn't allow to send non-files as form-data
    private val okHttp = OkHttpClient()

    @Test
    fun `text is uploaded correctly`() = TestUtil.test { app, http ->
        app.post("/test-upload") { it.result(it.uploadedFile("upload")!!.content.readBytes().toString(Charsets.UTF_8)) }
        val response = http.post("/test-upload")
            .field("upload", File("src/test/resources/upload-test/text.txt"))
            .asString()
        if (CRLF in response.body) {
            assertThat(response.body).isEqualTo(TEXT_FILE_CONTENT_CRLF)
        } else {
            assertThat(response.body).isEqualTo(TEXT_FILE_CONTENT_LF)
        }
    }

    @Test
    fun `mp3s are uploaded correctly`() = TestUtil.test { app, http ->
        app.post("/test-upload") { ctx ->
            val uf = ctx.uploadedFile("upload")!!
            ctx.json(UploadInfo(uf.filename, uf.size, uf.contentType, uf.extension))
        }
        val uploadFile = File("src/test/resources/upload-test/sound.mp3")
        val response = http.post("/test-upload")
            .field("upload", uploadFile)
            .asString()

        val uploadInfo = fasterJacksonMapper.fromJsonString(response.body, UploadInfo::class.java)
        assertThat(uploadInfo.size).isEqualTo(uploadFile.length())
        assertThat(uploadInfo.filename).isEqualTo(uploadFile.name)
        assertThat(uploadInfo.contentType).isEqualTo(ContentType.OCTET_STREAM)
        assertThat(uploadInfo.extension).isEqualTo(".mp3")
    }

    @Test
    fun `pngs are uploaded correctly`() = TestUtil.test { app, http ->
        app.post("/test-upload") { ctx ->
            val uf = ctx.uploadedFile("upload")
            ctx.json(UploadInfo(uf!!.filename, uf.size, uf.contentType, uf.extension))
        }
        val uploadFile = File("src/test/resources/upload-test/image.png")
        val response = http.post("/test-upload")
            .field("upload", uploadFile, ContentType.IMAGE_PNG.mimeType)
            .asString()
        val uploadInfo = fasterJacksonMapper.fromJsonString(response.body, UploadInfo::class.java)
        assertThat(uploadInfo.size).isEqualTo(uploadFile.length())
        assertThat(uploadInfo.filename).isEqualTo(uploadFile.name)
        assertThat(uploadInfo.contentType).isEqualTo(ContentType.IMAGE_PNG.mimeType)
        assertThat(uploadInfo.extension).isEqualTo(".png")
        assertThat(uploadInfo.size).isEqualTo(6690L)
    }

    @Test
    fun `multiple files are handled correctly`() = TestUtil.test { app, http ->
        app.post("/test-upload") { it.result(it.uploadedFiles("upload").size.toString()) }
        val response = http.post("/test-upload")
            .field("upload", File("src/test/resources/upload-test/image.png"))
            .field("upload", File("src/test/resources/upload-test/sound.mp3"))
            .field("upload", File("src/test/resources/upload-test/text.txt"))
            .asString()
        assertThat(response.body).isEqualTo("3")
    }

    @Test
    fun `uploadedFile doesn't throw for missing file`() = TestUtil.test { app, http ->
        app.post("/test-upload") { ctx ->
            ctx.uploadedFile("non-existing-file")
            ctx.result("OK")
        }
        assertThat(http.post("/test-upload").asString().body).isEqualTo("OK")
    }

    @Test
    fun `getting all files is handled correctly`() = TestUtil.test { app, http ->
        app.post("/test-upload") { ctx ->
            ctx.result(ctx.uploadedFiles().joinToString(", ") { it.filename })
        }
        val response = http.post("/test-upload")
            .field("upload", File("src/test/resources/upload-test/image.png"))
            .field("upload", File("src/test/resources/upload-test/sound.mp3"))
            .field("upload", File("src/test/resources/upload-test/text.txt"))
            .field("text-field", "text")
            .asString()
        assertThat(response.body).isEqualTo("image.png, sound.mp3, text.txt")
    }

    @Test
    fun `custom multipart properties applied correctly`() = TestUtil.test { app, http ->
        app.before("/test-upload") { ctx ->
            ctx.attribute("org.eclipse.jetty.multipartConfig", MultipartConfigElement("/tmp", 10, 10, 5))
        }

        app.post("/test-upload") { ctx ->
            ctx.result(ctx.uploadedFiles().joinToString(", ") { it.filename })
        }

        app.exception(IllegalStateException::class.java) { e, ctx ->
            ctx.result("${e::class.java.canonicalName} ${e.message}")
        }

        val response = http.post("/test-upload")
            .field("upload", File("src/test/resources/upload-test/image.png"))
            .field("text-field", "text")
            .asString()
        assertThat(response.body).isEqualTo("java.lang.IllegalStateException Request exceeds maxRequestSize (10)")
    }

    @Test
    fun `getting all files doesn't throw for non multipart request`() = TestUtil.test { app, http ->
        app.post("/test-upload") { it.result(it.uploadedFiles().joinToString("\n")) }

        val response = http.post("/test-upload")
            .header("content-type", ContentType.PLAIN)
            .body("")
            .asString()

        assertThat(response.body).isEqualTo("")
    }

    @Test
    fun `mixing files and text fields works`() = TestUtil.test { app, http ->
        app.post("/test-upload") { it.result(it.formParam("field") + " and " + it.uploadedFile("upload")!!.filename) }
        val response = http.post("/test-upload")
            .field("upload", File("src/test/resources/upload-test/image.png"))
            .field("field", "text-value")
            .asString()
        assertThat(response.body).isEqualTo("text-value and image.png")
    }

    @Test
    fun `mixing files and text fields works with multiple fields`() = TestUtil.test { app, http ->
        app.post("/test-upload") { it.result(it.formParam("field") + " and " + it.formParam("field2")) }
        val response = http.post("/test-upload")
            .field("upload", File("src/test/resources/upload-test/image.png"))
            .field("field", "text-value")
            .field("field2", "text-value-2")
            .asString()
        assertThat(response.body).isEqualTo("text-value and text-value-2")
    }

    @Test
    fun `unicode text-fields work`() = TestUtil.test { app, http ->
        app.post("/test-upload") { it.result(it.formParam("field") + " and " + it.uploadedFile("upload")!!.filename) }
        val response = http.post("/test-upload")
            .field("upload", File("src/test/resources/upload-test/text.txt"))
            .field("field", "♚♛♜♜♝♝♞♞♟♟♟♟♟♟♟♟")
            .asString()
        assertThat(response.body).isEqualTo("♚♛♜♜♝♝♞♞♟♟♟♟♟♟♟♟ and text.txt")
    }

    @Test
    fun `text-fields work`() = TestUtil.test { app, http ->
        app.post("/test-multipart-text-fields") { ctx ->
            val foosExtractedManually = ctx.formParamMap()["foo"]
            val foos = ctx.formParams("foo")
            val bar = ctx.formParam("bar")
            val baz = ctx.formParamAsClass<String>("baz").getOrDefault("default")
            ctx.result(
                "foos match: " + (foos == foosExtractedManually) + "\n"
                        + "foo: " + foos.joinToString(", ") + "\n"
                        + "bar: " + bar + "\n"
                        + "baz: " + baz
            )
        }
        val responseAsString = okHttp.newCall(
            Request.Builder().url(http.origin + "/test-multipart-text-fields").post(
                MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("foo", "foo-1")
                    .addFormDataPart("bar", "bar-1")
                    .addFormDataPart("foo", "foo-2")
                    .build()
            ).build()
        ).execute().body!!.string()
        val expectedContent = ("foos match: true" + "\n"
                + "foo: foo-1, foo-2" + "\n"
                + "bar: bar-1" + "\n"
                + "baz: default")
        assertThat(responseAsString).isEqualTo(expectedContent)
    }

    @Test
    fun `fields and files work in other client`() = TestUtil.test { app, http ->
        val prefix = "PREFIX: "
        val tempFile = File("src/test/resources/upload-test/text.txt")
        app.post("/test-multipart-file-and-text") { ctx ->
            val fileContent = ctx.uploadedFile("upload")!!.content.readBytes().toString(Charsets.UTF_8)
            ctx.result(ctx.formParam("prefix")!! + fileContent)
        }
        val responseAsString = okHttp.newCall(
            Request.Builder().url(http.origin + "/test-multipart-file-and-text").post(
                MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("prefix", prefix)
                    .addFormDataPart("upload", tempFile.name, tempFile.asRequestBody(ContentType.PLAIN.toMediaTypeOrNull()))
                    .build()
            ).build()
        ).execute().body!!.string()

        if (CRLF in responseAsString) {
            assertThat(responseAsString).isEqualTo(prefix + TEXT_FILE_CONTENT_CRLF)
        } else {
            assertThat(responseAsString).isEqualTo(prefix + TEXT_FILE_CONTENT_LF)
        }
    }

}
