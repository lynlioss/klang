# Media3 and Compose publish their consumer shrinker rules.

# SnakeYAML initializes loggers with Class.getPackage().getName(). Moving these
# classes into the default package makes getPackage() null on Android and breaks
# safe YAML loading before word timestamps can be read. Keep packages while
# allowing class/member shrinking, obfuscation and optimization.
-keeppackagenames org.yaml.snakeyaml.**
