from pathlib import Path
import shutil
import build

previous=Path((build.OUT/'verification/build-path.txt').read_text(encoding='utf-8'))
src=build.WORK/'tests'
shutil.copytree(build.ROOT/'tests',src)
classes=build.WORK/'test-classes';classes.mkdir()
res=build.WORK/'test-res';res.mkdir()
build.run([build.JDK/'bin/javac.exe','-encoding','UTF-8','--release','8','-classpath',str(build.ANDROID)+';'+str(previous/'build/classes'),'-d',classes,*src.glob('*.java')])
artifact=build.WORK/'tests.apk'
build.package(src/'AndroidManifest.xml',res,list(classes.rglob('*.class')),artifact,build.BUILD)
shutil.copyfile(artifact,build.OUT/'verification/tests.apk')
