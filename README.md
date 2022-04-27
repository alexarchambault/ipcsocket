## Scala CLI fork of sbt/ipcsocket (discontinued)

This repository is a fork of [`sbt/ipcsocket`](https://github.com/sbt/ipcsocket). Compared to it, it mainly:
- generates binaries on its CI rather than committing them upfront
- generates static libraries along with dynamic ones (static libraries can be statically linked in a GraalVM native image for example)
- pushed static and dynamic library files to Maven Central (see the `.so` / `.dylib` / `.dll` and `.a` / `.lib` files [here](https://repo1.maven.org/maven2/io/github/alexarchambault/tmp/ipcsocket/ipcsocket/1.4.1-aa-5-1/) for example)
- fixes / tweaks minor things (tries to shutdown input / output upon close)

This fork used to be used by Scala CLI, up to its `0.0.9` version (it switched to native domain socket support of Java 17 from `0.1.0`).
