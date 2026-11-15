# Media3 and Compose publish their consumer shrinker rules.

# SnakeYAML initializes loggers with Class.getPackage().getName(). Moving these
# classes into the default package makes getPackage() null on Android and breaks
# safe YAML loading before word timestamps can be read. Keep packages while
# allowing class/member shrinking, obfuscation and optimization.
-keeppackagenames org.yaml.snakeyaml.**

# Commons Compress registers ZIP extra-field implementations with Class.newInstance().
# yt-dlp unpacks its Python/FFmpeg archives through this code on first import.
# Without these constructors R8 turns the classes into non-instantiable types,
# and ExtraFieldUtils.<clinit> throws "class ... is not a concrete class".
-keep,allowobfuscation class * implements org.apache.commons.compress.archivers.zip.ZipExtraField {
    public <init>();
}
