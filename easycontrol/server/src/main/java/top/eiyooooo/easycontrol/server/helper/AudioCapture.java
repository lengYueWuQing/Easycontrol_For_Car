package top.eiyooooo.easycontrol.server.helper;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.AttributionSource;
import android.content.Context;
import android.media.*;
import android.os.Build;
import android.os.Looper;
import android.os.Parcel;
import top.eiyooooo.easycontrol.server.utils.L;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class AudioCapture {

    public static final int SAMPLE_RATE = 48000;
    public static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_STEREO;
    public static final int CHANNELS = 2;
    public static final int CHANNEL_MASK = AudioFormat.CHANNEL_IN_LEFT | AudioFormat.CHANNEL_IN_RIGHT;
    public static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    public static final int BYTES_PER_SAMPLE = 2;

    public static AudioRecord init() {
        AudioRecord recorder;
        // Android 16 开始限制通过 REMOTE_SUBMIX 采集播放音频（scrcpy #5698/#6021），优先使用 AudioPolicy 混音采集
        if (Build.VERSION.SDK_INT >= 36) {
            try {
                recorder = createAudioRecordByAudioPolicy();
                recorder.startRecording();
                return recorder;
            } catch (Throwable e) {
                L.w("Cannot create AudioRecord by AudioPolicy, fallback to REMOTE_SUBMIX", e);
            }
        }
        try {
            recorder = createAudioRecord();
        } catch (NullPointerException e) {
            L.w("Cannot create AudioRecord, try workaround");
            recorder = createAudioRecordWorkaround();
        }
        recorder.startRecording();
        return recorder;
    }

    public static int millisToBytes(int millis) {
        return (SAMPLE_RATE * CHANNELS * BYTES_PER_SAMPLE / 1000) * millis;
    }

    // 必须持有 policy 引用，否则会被系统注销导致采集失效
    private static Object registeredAudioPolicy;

    // Android 16+ 通过 AudioPolicy 混音采集播放音频（参考 scrcpy audio/AudioPlaybackCapture）：
    // AudioMixingRule rule = new AudioMixingRule.Builder()
    //         .setTargetMixRole(AudioMixingRule.MIX_ROLE_PLAYERS)
    //         .addMixRule(AudioMixingRule.RULE_MATCH_ATTRIBUTE_USAGE, usage)
    //         .voiceCommunicationCaptureAllowed(true)
    //         .build();
    // AudioMix mix = new AudioMix.Builder(rule).setRouteFlags(ROUTE_FLAG_LOOP_BACK | ROUTE_FLAG_RENDER).setFormat(format).build();
    // AudioPolicy policy = new AudioPolicy.Builder(context).addMix(mix).build();
    // AudioManager.registerAudioPolicyStatic(policy);
    // return policy.createAudioRecordSink(mix);
    @SuppressLint({"WrongConstant", "MissingPermission"})
    private static AudioRecord createAudioRecordByAudioPolicy() throws Exception {
        Class<?> mixingRuleClass = Class.forName("android.media.audiopolicy.AudioMixingRule");
        Class<?> mixingRuleBuilderClass = Class.forName("android.media.audiopolicy.AudioMixingRule$Builder");
        Class<?> audioMixClass = Class.forName("android.media.audiopolicy.AudioMix");
        Class<?> audioMixBuilderClass = Class.forName("android.media.audiopolicy.AudioMix$Builder");
        Class<?> audioPolicyBuilderClass = Class.forName("android.media.audiopolicy.AudioPolicy$Builder");
        Class<?> audioPolicyClass = Class.forName("android.media.audiopolicy.AudioPolicy");

        // 构建 AudioMixingRule
        Object ruleBuilder = mixingRuleBuilderClass.getDeclaredConstructor().newInstance();
        mixingRuleBuilderClass.getMethod("setTargetMixRole", int.class)
                .invoke(ruleBuilder, getStaticInt(mixingRuleClass, "MIX_ROLE_PLAYERS", 0));
        Method addMixRuleMethod = mixingRuleBuilderClass.getMethod("addMixRule", int.class, Object.class);
        int usageRule = getStaticInt(mixingRuleClass, "RULE_MATCH_ATTRIBUTE_USAGE", 1);
        int[] usages = {AudioAttributes.USAGE_UNKNOWN, AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME};
        for (int usage : usages) {
            AudioAttributes attributes = new AudioAttributes.Builder().setUsage(usage).build();
            addMixRuleMethod.invoke(ruleBuilder, usageRule, attributes);
        }
        // 允许采集语音通信音频（部分 Android 16 版本新增，低版本无此方法）
        try {
            mixingRuleBuilderClass.getMethod("voiceCommunicationCaptureAllowed", boolean.class).invoke(ruleBuilder, true);
        } catch (NoSuchMethodException ignored) {
        }
        Object mixingRule = mixingRuleBuilderClass.getMethod("build").invoke(ruleBuilder);

        // 构建 AudioMix，ROUTE_FLAG_LOOP_BACK 采集且 ROUTE_FLAG_RENDER 保证设备继续外放
        Constructor<?> mixBuilderConstructor = audioMixBuilderClass.getDeclaredConstructor(mixingRuleClass);
        mixBuilderConstructor.setAccessible(true);
        Object mixBuilder = mixBuilderConstructor.newInstance(mixingRule);
        int routeFlags = getStaticInt(audioMixClass, "ROUTE_FLAG_LOOP_BACK", 2) | getStaticInt(audioMixClass, "ROUTE_FLAG_RENDER", 1);
        audioMixBuilderClass.getMethod("setRouteFlags", int.class).invoke(mixBuilder, routeFlags);
        // 尽量对齐编码器格式（48kHz/双声道/PCM16）
        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(ENCODING)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNEL_CONFIG)
                .build();
        try {
            audioMixBuilderClass.getMethod("setFormat", AudioFormat.class).invoke(mixBuilder, format);
        } catch (NoSuchMethodException ignored) {
        }
        Object audioMix = audioMixBuilderClass.getMethod("build").invoke(mixBuilder);

        // 构建 AudioPolicy
        Object policyBuilder = audioPolicyBuilderClass.getDeclaredConstructor(Context.class).newInstance(FakeContext.get());
        audioPolicyBuilderClass.getMethod("addMix", audioMixClass).invoke(policyBuilder, audioMix);
        Object policy = audioPolicyBuilderClass.getMethod("build").invoke(policyBuilder);

        // 注册 policy 并创建 AudioRecord
        AudioManager.class.getMethod("registerAudioPolicyStatic", audioPolicyClass).invoke(null, policy);
        AudioRecord audioRecord = (AudioRecord) audioPolicyClass.getMethod("createAudioRecordSink", audioMixClass)
                .invoke(policy, audioMix);
        registeredAudioPolicy = policy;
        return audioRecord;
    }

    private static int getStaticInt(Class<?> cls, String fieldName, int defaultValue) {
        try {
            return cls.getField(fieldName).getInt(null);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    @TargetApi(Build.VERSION_CODES.M)
    @SuppressLint({"WrongConstant", "MissingPermission"})
    private static AudioRecord createAudioRecord() {
        AudioRecord.Builder audioRecordBuilder = new AudioRecord.Builder();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audioRecordBuilder.setContext(FakeContext.get());

        audioRecordBuilder.setAudioSource(MediaRecorder.AudioSource.REMOTE_SUBMIX);
        AudioFormat.Builder audioFormatBuilder = new AudioFormat.Builder();
        audioFormatBuilder.setEncoding(ENCODING);
        audioFormatBuilder.setSampleRate(SAMPLE_RATE);
        audioFormatBuilder.setChannelMask(CHANNEL_CONFIG);
        audioRecordBuilder.setAudioFormat(audioFormatBuilder.build());
        audioRecordBuilder.setBufferSizeInBytes(16 * AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, ENCODING));
        return audioRecordBuilder.build();
    }

    @TargetApi(Build.VERSION_CODES.R)
    @SuppressLint("WrongConstant,MissingPermission,BlockedPrivateApi,SoonBlockedPrivateApi,DiscouragedPrivateApi")
    private static AudioRecord createAudioRecordWorkaround() {
        try {
            // AudioRecord audioRecord = new AudioRecord(0L);
            Constructor<AudioRecord> audioRecordConstructor = AudioRecord.class.getDeclaredConstructor(long.class);
            audioRecordConstructor.setAccessible(true);
            AudioRecord audioRecord = audioRecordConstructor.newInstance(0L);

            // audioRecord.mRecordingState = RECORDSTATE_STOPPED;
            Field mRecordingStateField = AudioRecord.class.getDeclaredField("mRecordingState");
            mRecordingStateField.setAccessible(true);
            mRecordingStateField.set(audioRecord, AudioRecord.RECORDSTATE_STOPPED);

            Looper looper = Looper.myLooper();
            if (looper == null) {
                looper = Looper.getMainLooper();
            }

            // audioRecord.mInitializationLooper = looper;
            Field mInitializationLooperField = AudioRecord.class.getDeclaredField("mInitializationLooper");
            mInitializationLooperField.setAccessible(true);
            mInitializationLooperField.set(audioRecord, looper);

            // Create `AudioAttributes` with fixed capture preset
            AudioAttributes.Builder audioAttributesBuilder = new AudioAttributes.Builder();
            Method setInternalCapturePresetMethod = AudioAttributes.Builder.class.getMethod("setInternalCapturePreset", int.class);
            setInternalCapturePresetMethod.invoke(audioAttributesBuilder, MediaRecorder.AudioSource.REMOTE_SUBMIX);
            AudioAttributes attributes = audioAttributesBuilder.build();

            // audioRecord.mAudioAttributes = attributes;
            Field mAudioAttributesField = AudioRecord.class.getDeclaredField("mAudioAttributes");
            mAudioAttributesField.setAccessible(true);
            mAudioAttributesField.set(audioRecord, attributes);

            // audioRecord.audioParamCheck(capturePreset, sampleRate, encoding);
            Method audioParamCheckMethod = AudioRecord.class.getDeclaredMethod("audioParamCheck", int.class, int.class, int.class);
            audioParamCheckMethod.setAccessible(true);
            audioParamCheckMethod.invoke(audioRecord, MediaRecorder.AudioSource.REMOTE_SUBMIX, SAMPLE_RATE, ENCODING);

            // audioRecord.mChannelCount = channels
            Field mChannelCountField = AudioRecord.class.getDeclaredField("mChannelCount");
            mChannelCountField.setAccessible(true);
            mChannelCountField.set(audioRecord, CHANNELS);

            // audioRecord.mChannelMask = channelMask
            Field mChannelMaskField = AudioRecord.class.getDeclaredField("mChannelMask");
            mChannelMaskField.setAccessible(true);
            mChannelMaskField.set(audioRecord, CHANNEL_MASK);

            int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, ENCODING);
            int bufferSizeInBytes = minBufferSize * 8;

            // audioRecord.audioBuffSizeCheck(bufferSizeInBytes)
            Method audioBuffSizeCheckMethod = AudioRecord.class.getDeclaredMethod("audioBuffSizeCheck", int.class);
            audioBuffSizeCheckMethod.setAccessible(true);
            audioBuffSizeCheckMethod.invoke(audioRecord, bufferSizeInBytes);

            final int channelIndexMask = 0;

            int[] sampleRateArray = new int[]{SAMPLE_RATE};
            int[] session = new int[]{AudioManager.AUDIO_SESSION_ID_GENERATE};

            int initResult;
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                // private native final int native_setup(Object audiorecord_this,
                // Object /*AudioAttributes*/ attributes,
                // int[] sampleRate, int channelMask, int channelIndexMask, int audioFormat,
                // int buffSizeInBytes, int[] sessionId, String opPackageName,
                // long nativeRecordInJavaObj);
                Method nativeSetupMethod = AudioRecord.class.getDeclaredMethod("native_setup", Object.class, Object.class, int[].class, int.class,
                        int.class, int.class, int.class, int[].class, String.class, long.class);
                nativeSetupMethod.setAccessible(true);
                initResult = (int) nativeSetupMethod.invoke(audioRecord, new WeakReference<AudioRecord>(audioRecord), attributes, sampleRateArray,
                        CHANNEL_MASK, channelIndexMask, audioRecord.getAudioFormat(), bufferSizeInBytes, session, FakeContext.get().getOpPackageName(),
                        0L);
            } else {
                // Assume `context` is never `null`
                AttributionSource attributionSource = FakeContext.get().getAttributionSource();

                // Assume `attributionSource.getPackageName()` is never null

                // ScopedParcelState attributionSourceState = attributionSource.asScopedParcelState()
                Method asScopedParcelStateMethod = AttributionSource.class.getDeclaredMethod("asScopedParcelState");
                asScopedParcelStateMethod.setAccessible(true);

                try (AutoCloseable attributionSourceState = (AutoCloseable) asScopedParcelStateMethod.invoke(attributionSource)) {
                    Method getParcelMethod = attributionSourceState.getClass().getDeclaredMethod("getParcel");
                    Parcel attributionSourceParcel = (Parcel) getParcelMethod.invoke(attributionSourceState);

                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        // private native int native_setup(Object audiorecordThis,
                        // Object /*AudioAttributes*/ attributes,
                        // int[] sampleRate, int channelMask, int channelIndexMask, int audioFormat,
                        // int buffSizeInBytes, int[] sessionId, @NonNull Parcel attributionSource,
                        // long nativeRecordInJavaObj, int maxSharedAudioHistoryMs);
                        Method nativeSetupMethod = AudioRecord.class.getDeclaredMethod("native_setup", Object.class, Object.class, int[].class,
                                int.class, int.class, int.class, int.class, int[].class, Parcel.class, long.class, int.class);
                        nativeSetupMethod.setAccessible(true);
                        initResult = (int) nativeSetupMethod.invoke(audioRecord, new WeakReference<AudioRecord>(audioRecord), attributes,
                                sampleRateArray, CHANNEL_MASK, channelIndexMask, audioRecord.getAudioFormat(), bufferSizeInBytes, session,
                                attributionSourceParcel, 0L, 0);
                    } else {
                        // Android 14 added a new int parameter "halInputFlags"
                        // <https://github.com/aosp-mirror/platform_frameworks_base/commit/f6135d75db79b1d48fad3a3b3080d37be20a2313>
                        Method nativeSetupMethod = AudioRecord.class.getDeclaredMethod("native_setup", Object.class, Object.class, int[].class,
                                int.class, int.class, int.class, int.class, int[].class, Parcel.class, long.class, int.class, int.class);
                        nativeSetupMethod.setAccessible(true);
                        initResult = (int) nativeSetupMethod.invoke(audioRecord, new WeakReference<AudioRecord>(audioRecord), attributes,
                                sampleRateArray, CHANNEL_MASK, channelIndexMask, audioRecord.getAudioFormat(), bufferSizeInBytes, session,
                                attributionSourceParcel, 0L, 0, 0);
                    }
                }
            }

            if (initResult != AudioRecord.SUCCESS) {
                L.w("AudioRecord initialization failed with code " + initResult);
                throw new RuntimeException("Cannot create AudioRecord");
            }

            // mSampleRate = sampleRate[0]
            Field mSampleRateField = AudioRecord.class.getDeclaredField("mSampleRate");
            mSampleRateField.setAccessible(true);
            mSampleRateField.set(audioRecord, sampleRateArray[0]);

            // audioRecord.mSessionId = session[0]
            Field mSessionIdField = AudioRecord.class.getDeclaredField("mSessionId");
            mSessionIdField.setAccessible(true);
            mSessionIdField.set(audioRecord, session[0]);

            // audioRecord.mState = AudioRecord.STATE_INITIALIZED
            Field mStateField = AudioRecord.class.getDeclaredField("mState");
            mStateField.setAccessible(true);
            mStateField.set(audioRecord, AudioRecord.STATE_INITIALIZED);

            return audioRecord;
        } catch (Exception e) {
            L.e("Cannot create AudioRecord", e);
            throw new RuntimeException("Cannot create AudioRecord");
        }
    }
}
