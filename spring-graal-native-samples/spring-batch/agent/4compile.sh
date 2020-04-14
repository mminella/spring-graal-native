native-image \
  -Dspring.graal.mode=initialization-only \
  --no-server \
  --no-fallback \
  -H:+TraceClassInitialization \
  -H:+ReportExceptionStackTraces \
  -H:Name=spring-batch-agent \
  -cp .:$CP:graal:../../../../../../spring-graal-native/target/spring-graal-native-0.7.0.BUILD-SNAPSHOT.jar \
  com.example.batch.BatchConfiguration 2>&1 | tee output.txt
