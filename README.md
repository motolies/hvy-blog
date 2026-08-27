# hvy-blog


## Postman
- 워크스페이스: blog / 컬렉션: hvy-blog
- 컨트롤러 수정 시 대응 요청도 함께 갱신할 것
- 인증은 컬렉션 레벨 Bearer {{jwt_token}} 상속, 요청마다 재정의 금지
- base_url 만들어서 환경 분리 
- 폴더 구조는 컨트롤러 클래스 단위로 유지
- environment 환경별로 생성 