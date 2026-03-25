#include <jni.h>

#include <string>
#include <vector>

extern "C" int omconvert_cli_main(int argc, char **argv);

extern "C"
JNIEXPORT jint JNICALL
Java_org_openmovement_omgui_android_export_OmConvertNative_runOmconvert(
    JNIEnv *env,
    jobject /* thiz */,
    jobjectArray arguments
) {
    std::vector<std::string> storage;
    std::vector<char *> argv;

    storage.emplace_back("omconvert");
    argv.push_back(storage.back().data());

    const jsize length = env->GetArrayLength(arguments);
    storage.reserve(static_cast<size_t>(length) + 1);
    argv.reserve(static_cast<size_t>(length) + 1);

    for (jsize i = 0; i < length; ++i) {
        auto *value = static_cast<jstring>(env->GetObjectArrayElement(arguments, i));
        const char *chars = env->GetStringUTFChars(value, nullptr);
        storage.emplace_back(chars != nullptr ? chars : "");
        env->ReleaseStringUTFChars(value, chars);
        env->DeleteLocalRef(value);
        argv.push_back(storage.back().data());
    }

    return static_cast<jint>(omconvert_cli_main(static_cast<int>(argv.size()), argv.data()));
}
