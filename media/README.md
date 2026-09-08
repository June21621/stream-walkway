# media

직접 촬영한 영상을 두는 자리다. `CAPTURE_SOURCE=file` 일 때 youtube-service 가
여기서 프레임을 뽑는다.

**git 에 올라가지 않는다** (`.gitignore`). 영상은 저장소에 넣을 물건이 아니고,
사람마다 다른 파일을 쓸 수 있다.

컨테이너에는 `/media` 로 읽기 전용 마운트된다. `.env` 의 `CAPTURE_FILE_PATH` 는
호스트 경로가 아니라 **컨테이너 경로**를 쓴다.

```
CAPTURE_SOURCE=file
CAPTURE_FILE_PATH=/media/<파일명>.mp4
```

웹에서 받은 영상·사진을 넣지 말 것. 이 프로젝트는 이용약관 문제로 YouTube
어댑터를 만들지 않기로 했고(`apps/youtube-service/src/capture.js` 주석),
같은 이유가 여기에도 적용된다.
