#!/usr/bin/env python3
"""Tag supplied MP3s without transcoding, using the release artwork from ncs.io."""
import json, re, shutil, urllib.request
from pathlib import Path
from io import BytesIO
from PIL import Image
from mutagen.mp3 import MP3
from mutagen.mp4 import MP4, MP4Cover
from mutagen.id3 import TIT2, TPE1, TALB, APIC, TCON, WXXX
root = Path(__file__).resolve().parents[1]
tracks = [
 ('kyoto', 'KYOTO', 'Hester', '0c9186d3-27d5-4396-81bb-b02310bafbcd-023ccf37-bc58-4ad1-88af-ceaf8cc167c0-mp3.mp3'),
 ('recall', 'Recall', 'gabriawll', '0c9186d3-27d5-4396-81bb-b02310bafbcd-41a98f20-1b37-4ea4-bb4a-bb84105b78fc-mp3.mp3'),
 ('eyes-on-us', 'EYES ON US', '2frers', '0c9186d3-27d5-4396-81bb-b02310bafbcd-e349ece0-604e-49f0-8534-3f7655bcbcd3-mp3.mp3')
]
catalog = []
for slug, title, artist, source in tracks:
 url = 'https://ncs.io/' + ('EYESONUS' if slug == 'eyes-on-us' else slug)
 request = urllib.request.Request(url, headers={'User-Agent':'Mozilla/5.0'})
 page = urllib.request.urlopen(request, timeout=60).read().decode()
 artwork_url = re.search(r'<meta[^>]+property="og:image"[^>]+content="([^"]+)"', page).group(1)
 image = Image.open(BytesIO(urllib.request.urlopen(artwork_url, timeout=60).read())).convert('RGB')
 image.thumbnail((900,900))
 buffer=BytesIO(); image.save(buffer, 'JPEG', quality=92)
 art=buffer.getvalue()
 (root / 'app/src/main/res/drawable-nodpi' / (slug.replace('-','_')+'.jpg')).write_bytes(art)
 dest=root/'app/src/main/assets/starter'/f'{artist} - {title}.mp3'
 shutil.copyfile(Path('/root/.t3/userdata/attachments')/source, dest)
 mp3=MP3(dest)
 if mp3.tags is None: mp3.add_tags()
 for key in ('TIT2','TPE1','TALB','APIC','TCON','WXXX'): mp3.tags.delall(key)
 mp3.tags.add(TIT2(encoding=3,text=title)); mp3.tags.add(TPE1(encoding=3,text=artist))
 mp3.tags.add(TALB(encoding=3,text=title+' [NCS Release]'))
 mp3.tags.add(TCON(encoding=3,text='Electronic'))
 mp3.tags.add(APIC(encoding=3,mime='image/jpeg',type=3,desc='Cover',data=art))
 mp3.tags.add(WXXX(encoding=3,desc='Release',url=url)); mp3.save(v2_version=3)
 catalog.append(dict(file=dest.name,title=title,artist=artist,album=title+' [NCS Release]',durationMs=int(mp3.info.length*1000),releaseUrl=url,artworkUrl=artwork_url))
 print(title, artist, round(mp3.info.length), artwork_url)
# Prefer the user's tagged M4As; retain embedded title, artist and cover.
m4a_sources=[
 '0c9186d3-27d5-4396-81bb-b02310bafbcd-2590a834-e42a-4762-a228-6b0c686d5ee7-m4a.m4a',
 '0c9186d3-27d5-4396-81bb-b02310bafbcd-807cc144-8017-48d9-84cc-4351a046e7bd-m4a.m4a',
 '0c9186d3-27d5-4396-81bb-b02310bafbcd-4b007b8b-8652-4dc0-a689-f673e4bb923c-m4a.m4a',
]
for i, name in enumerate(m4a_sources):
 source=Path('/root/.t3/userdata/attachments')/name
 if not source.exists(): continue
 slug,title,artist,_=tracks[i]
 dest=root/'app/src/main/assets/starter'/f'{artist} - {title}.m4a'
 shutil.copyfile(source,dest)
 m4a=MP4(dest)
 if not m4a.tags.get('©alb'): m4a.tags['©alb']=[title+' [NCS Release]']
 if not m4a.tags.get('covr'):
  m4a.tags['covr']=[MP4Cover((root/'app/src/main/res/drawable-nodpi'/(slug.replace('-','_')+'.jpg')).read_bytes(),imageformat=MP4Cover.FORMAT_JPEG)]
 m4a.save()
 cover=Image.open(BytesIO(m4a.tags['covr'][0])).convert('RGB'); cover.thumbnail((900,900))
 cover.save(root/'app/src/main/res/drawable-nodpi'/(slug.replace('-','_')+'.jpg'),quality=92)
 (root/'app/src/main/assets/starter'/catalog[i]['file']).unlink(missing_ok=True)
 catalog[i].update(file=dest.name,title=m4a.tags.get('©nam',[title])[0],artist=m4a.tags.get('©ART',[artist])[0],album=m4a.tags['©alb'][0],durationMs=int(m4a.info.length*1000))
(root/'app/src/main/assets/starter/catalog.json').write_text(json.dumps(catalog,indent=2)+'\n')
