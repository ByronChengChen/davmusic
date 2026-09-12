# 添加项目特定的 ProGuard 规则
# 参考 https://www.guardsquare.com/manual/configuration/examples

# 保留 OkHttp 相关的类
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# 保留 WebDAV 相关的模型类
-keep class com.byron.davmusic.** {
    public *;
}

# 保留 Jackson 注解（虽然我们没有使用，但保留以防以后使用）
-keepattributes *Annotation*

# 保留类成员，以便通过反射访问
-keepclassmembers class ** {
    public *;
}

# 保留 native 方法
-keepclasseswithmembernames class * {
    native <methods>;
}

# 保留枚举类
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# 保留 Parcelable 实现
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# 保留序列化类
-keepnames class * implements java.io.Serializable

# 保留 Serializable 的序列化 ID
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}
