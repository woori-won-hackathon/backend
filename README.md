# 백엔드 설정 및 실행

1. MySQL을 실행하고 데이터베이스를 생성합니다.

   ```sql
   CREATE DATABASE IF NOT EXISTS wonway
     CHARACTER SET utf8mb4
     COLLATE utf8mb4_unicode_ci;
   ```

2. `backend/.env`를 생성합니다.

   ```dotenv
   DB_USERNAME=로컬_MySQL_사용자명
   DB_PASSWORD=로컬_MySQL_비밀번호
   OPENAI_API_KEY=OpenAI_API_키
   SPRING_PROFILES_ACTIVE=local,policy-demo
   SPRING_DATASOURCE_URL=jdbc:mysql://localhost:3306/wonway?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul&characterEncoding=UTF-8
   POLICY_SEED_ENABLED=false
   POLICY_SEED_LOCATION=classpath:policy/policies.json
   ```

3. 정책 데이터를 적재합니다.

   ```sh
   cd backend
   POLICY_MYSQL_IMPORT=true POLICY_SEED_LOCATION=classpath:policy/policies.json ./gradlew test --tests 'com.example.wonway.policy.persistence.PolicyMySqlImportTest' --no-daemon --rerun-tasks
   ```

4. 백엔드를 실행합니다.

   ```sh
   ./gradlew bootRun
   ```

5. 로그에서 아래 문구를 확인합니다.

   ```text
   Started WonwayApplication
   ```

6. 백엔드가 실행된 상태에서 [프런트엔드 실행 방법](../frontend/README.md)을 진행합니다.

7. 종료하려면 백엔드 터미널에서 `Ctrl+C`를 누릅니다.
