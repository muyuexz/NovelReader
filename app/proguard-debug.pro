#第38批 F刀：debug 交付通道的 R8 规则。
#
# 这一刀让 assembleDebug 也跑 R8（缩未用代码 + 缩未用资源），
# 但**不混淆**：CrashLogger 写出的 nr_crash.log 依赖可读的类名/方法名定位，
# 一旦混淆，后续排障就得靠 mapping 反查，代价远大于那点体积收益。
-dontobfuscate

# 保住注解元数据：@Immutable / @Stable / @Serializable 等运行时虽不直接引用，
# 但调试工具链（layout inspector / 反查稳定性判定）会读，显式保留避免被裁。
-keepattributes RuntimeVisibleAnnotations, RuntimeInvisibleAnnotations
