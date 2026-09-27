#!/data/data/com.termux/files/usr/bin/python3
import pathlib, secrets, subprocess, os
home=pathlib.Path.home(); src=pathlib.Path(__file__).parent
out=home/'.local/libexec/task-review';out.mkdir(parents=True,exist_ok=True)
classes=out/'classes';classes.mkdir(exist_ok=True)
dex=out/'dex';dex.mkdir(exist_ok=True)
jar=home/'.local/libexec/ghost-display/android-35.jar'
subprocess.run(['javac','-source','8','-target','8','-cp',str(jar),
 '-d',str(classes),str(src/'TaskStatusReceiver.java'),
 str(src/'TaskReviewActivity.java')],check=True)
subprocess.run(['d8','--lib',str(jar),'--output',str(dex),
 *map(str,classes.rglob('*.class'))],check=True)
unsigned=out/'unsigned.apk'
subprocess.run(['aapt2','link','-I',str(jar),'--manifest',
 str(src/'AndroidManifest.xml'),'-o',str(unsigned)],check=True)
subprocess.run(['jar','uf',str(unsigned),'-C',str(dex),'classes.dex'],check=True)
key=out/'signing.keystore';password=out/'signing.password'
if not key.exists():
 secret=secrets.token_urlsafe(24);password.write_text(secret);os.chmod(password,0o600)
 subprocess.run(['keytool','-genkeypair','-keyalg','RSA','-keysize','2048',
  '-validity','3650','-alias','taskreview','-dname','CN=Task Review',
  '-keystore',str(key),'-storepass',secret,'-keypass',secret],check=True,
  stdout=subprocess.DEVNULL)
secret=password.read_text()
apk=out/'task-review.apk'
subprocess.run(['apksigner','sign','--ks',str(key),'--ks-key-alias','taskreview',
 '--ks-pass','pass:'+secret,'--key-pass','pass:'+secret,
 '--out',str(apk),str(unsigned)],check=True)
subprocess.run(['apksigner','verify',str(apk)],check=True)
print(apk)

