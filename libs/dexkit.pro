# build.sh 用 R8 只裁剪第三方库（DexKit 及其依赖 kotlin-stdlib、flatbuffers），模块自身代码不经过 R8。
# DexKit 整体保留（JNI 按名字回调），只去掉 kotlin-stdlib / flatbuffers 中用不到的类。
-dontobfuscate
-dontoptimize
-keepattributes *
-keep class org.luckypray.dexkit.** { *; }
-keepclasseswithmembers,includedescriptorclasses class org.luckypray.dexkit.** {
    native <methods>;
}
-dontwarn org.jetbrains.annotations.**
-dontwarn kotlin.**
# 以 classfile 模式输出时 lambda / 字符串拼接仍是 invokedynamic，android.jar 里没有这两个引导类；随后 d8 会脱糖
-dontwarn java.lang.invoke.LambdaMetafactory
-dontwarn java.lang.invoke.StringConcatFactory
