NationWar v3 - 국가 전쟁 서버 플러그인 (Paper 26.2 / Java 25)

[jar 만드는 법 - 둘 중 하나]
A) 설치 없이 (추천): 이 폴더 전체를 GitHub 저장소에 올리면 자동 빌드됩니다.
   저장소 > Actions > "Build NationWar" > 완료된 실행 > Artifacts 의 NationWar-plugin 다운로드
   (.github/workflows/build.yml 이 JDK 25로 빌드)
B) 내 PC: JDK 25 + Maven 설치 후 build.bat(윈도우) 또는 build.sh 실행
   -> target/NationWar-3.0.0.jar

만들어진 jar 를 서버 plugins 폴더에 넣고 재시작하면 끝입니다.
(빌드가 의존성 오류로 실패하면 pom.xml 의 paper-api 버전을 PaperMC 사이트에서 확인한 26.2 버전으로 바꾸세요.)

[리소스팩 (돈 수표 모양)]
NationWar-ResourcePack.zip 을 웹에 올리고 server.properties 에
  resource-pack=<zip 직접 다운로드 URL>
  resource-pack-sha1=b4d905edd8478e50f1d584d2e914ce77b6e4e398
(없어도 수표는 종이로 보이며 정상 작동)

[처음 설정]
- 모든 /국가 명령어는 기본 관리자 전용입니다. 일반 유저에게 열어주려면:
    /국가관리 명령어 건국 허용      /국가관리 명령어 전체 허용
    /국가관리 명령어목록
- 돈은 상점 판매로 법니다. 테스트용 지급: /국가관리 돈지급 <플레이어> <금액>
- 최초 실행 시 스폰이 구역 중심으로 저장(1만x1만). 다시 맞추기: /국가관리 구역설정 [크기]

[플레이]
- 상태창: Shift+우클릭 (뭘 들고 있어도) 또는 Shift+F (빈손으로 허공을 볼 때)
- 땅 구매: 금 괭이로 블록 우클릭 -> 다른 블록 좌클릭 (위에서 본 1칸당 10원)
- 건국: 땅을 선택한 뒤 상태창 > 땅 관리 > 건국 (채팅으로 이름 입력)
