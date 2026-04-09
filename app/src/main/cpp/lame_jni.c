#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include "lame/include/lame.h"
#include <android/log.h>

#define LOG_TAG "LameJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static lame_global_flags *gfp = NULL;

JNIEXPORT void JNICALL
Java_com_mixtape_app_LameEncoder_nativeInit(
    JNIEnv *env, jobject thiz,
    jint inSampleRate, jint inChannels,
    jint outSampleRate, jint outBitRate, jint quality) {

    if (gfp != NULL) {
        lame_close(gfp);
    }

    gfp = lame_init();
    if (gfp == NULL) {
        LOGE("Failed to initialize LAME");
        return;
    }

    lame_set_in_samplerate(gfp, inSampleRate);
    lame_set_num_channels(gfp, inChannels);
    lame_set_out_samplerate(gfp, outSampleRate);
    lame_set_brate(gfp, outBitRate / 1000); // LAME takes kbps
    lame_set_quality(gfp, quality); // 2=high, 5=medium, 7=fast
    lame_set_mode(gfp, inChannels == 1 ? MONO : JOINT_STEREO);

    int ret = lame_init_params(gfp);
    if (ret < 0) {
        LOGE("lame_init_params failed: %d", ret);
        lame_close(gfp);
        gfp = NULL;
    }

    LOGI("LAME initialized: in=%dHz/%dch out=%dHz/%dkbps q=%d",
         inSampleRate, inChannels, outSampleRate, outBitRate / 1000, quality);
}

JNIEXPORT jint JNICALL
Java_com_mixtape_app_LameEncoder_nativeEncode(
    JNIEnv *env, jobject thiz,
    jshortArray leftChannel, jshortArray rightChannel,
    jint numSamples, jbyteArray mp3Buffer) {

    if (gfp == NULL) return -1;

    jshort *left = (*env)->GetShortArrayElements(env, leftChannel, NULL);
    jshort *right = rightChannel != NULL ?
        (*env)->GetShortArrayElements(env, rightChannel, NULL) : NULL;

    jint mp3BufSize = (*env)->GetArrayLength(env, mp3Buffer);
    jbyte *mp3Buf = (*env)->GetByteArrayElements(env, mp3Buffer, NULL);

    int result;
    if (right != NULL) {
        result = lame_encode_buffer(gfp,
            left, right, numSamples,
            (unsigned char *)mp3Buf, mp3BufSize);
    } else {
        result = lame_encode_buffer(gfp,
            left, left, numSamples,
            (unsigned char *)mp3Buf, mp3BufSize);
    }

    (*env)->ReleaseShortArrayElements(env, leftChannel, left, 0);
    if (right != NULL) {
        (*env)->ReleaseShortArrayElements(env, rightChannel, right, 0);
    }
    (*env)->ReleaseByteArrayElements(env, mp3Buffer, mp3Buf, 0);

    return result;
}

JNIEXPORT jint JNICALL
Java_com_mixtape_app_LameEncoder_nativeEncodeInterleaved(
    JNIEnv *env, jobject thiz,
    jshortArray pcmData, jint numSamples, jbyteArray mp3Buffer) {

    if (gfp == NULL) return -1;

    jshort *pcm = (*env)->GetShortArrayElements(env, pcmData, NULL);
    jint mp3BufSize = (*env)->GetArrayLength(env, mp3Buffer);
    jbyte *mp3Buf = (*env)->GetByteArrayElements(env, mp3Buffer, NULL);

    int result = lame_encode_buffer_interleaved(gfp,
        pcm, numSamples,
        (unsigned char *)mp3Buf, mp3BufSize);

    (*env)->ReleaseShortArrayElements(env, pcmData, pcm, 0);
    (*env)->ReleaseByteArrayElements(env, mp3Buffer, mp3Buf, 0);

    return result;
}

JNIEXPORT jint JNICALL
Java_com_mixtape_app_LameEncoder_nativeFlush(
    JNIEnv *env, jobject thiz, jbyteArray mp3Buffer) {

    if (gfp == NULL) return -1;

    jint mp3BufSize = (*env)->GetArrayLength(env, mp3Buffer);
    jbyte *mp3Buf = (*env)->GetByteArrayElements(env, mp3Buffer, NULL);

    int result = lame_encode_flush(gfp,
        (unsigned char *)mp3Buf, mp3BufSize);

    (*env)->ReleaseByteArrayElements(env, mp3Buffer, mp3Buf, 0);

    return result;
}

JNIEXPORT void JNICALL
Java_com_mixtape_app_LameEncoder_nativeClose(
    JNIEnv *env, jobject thiz) {

    if (gfp != NULL) {
        lame_close(gfp);
        gfp = NULL;
    }
}
