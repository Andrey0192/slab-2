Мой пример запуска 

``` runServer
java -cp out org.example.Main server 5000 
```
``` runClient
java -cp out org.example.Main client C:\Users\PC\IdeaProjects\slab-2\uploads\123.mp4 127.0.0.1 5000
```

```
./gradlew clean build
```

```
./gradlew run --args="server 5000"
```

```
./gradlew run --args='client "/path/to/123.mp4" 127.0.0.1 5000'
```
