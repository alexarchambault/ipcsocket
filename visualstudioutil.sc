
private def vcVersions = Seq("2019", "2017")
private def vcEditions = Seq("Enterprise", "Community", "BuildTools")
private lazy val vcvarsCandidates = Option(System.getenv("VCVARSALL")) ++ {
  for {
    version <- vcVersions
    edition <- vcEditions
  } yield """C:\Program Files (x86)\Microsoft Visual Studio\""" + version + "\\" + edition + """\VC\Auxiliary\Build\vcvars64.bat"""
}

private def vcvarsOpt: Option[os.Path] =
  vcvarsCandidates
    .iterator
    .map(os.Path(_, os.pwd))
    .filter(os.exists(_))
    .toStream
    .headOption

lazy val vcvars = vcvarsOpt.getOrElse {
  sys.error("vcvars64.bat not found. Ensure Visual Studio is installed, or put the vcvars64.bat path in VCVARSALL.")
}
