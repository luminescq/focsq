cmake_minimum_required(VERSION 3.13)
project(runner LANGUAGES C CXX)

# Define the application target. To change its name, change BINARY_NAME in the
# top-level CMakeLists.txt, not the value here, as `flutter run` will no longer
# work.
#
# Any new source files that you add to the application should be added here.
add_executable(${BINARY_NAME}
  "main.cc"
  "my_application.cc"
  "${FLUTTER_MANAGED_DIR}/generated_plugin_registrant.cc"
)

# [FOCSQ] Обёртка запуска с cap_net_admin: setcap на самом бинарнике
# переводит процесс в AT_SECURE, и $ORIGIN в RUNPATH перестаёт
# работать (приложение не находит собственные lib/*.so). Capability
# выдаётся focsq-tun; она поднимает её в ambient и exec'ает бинарник
# — тот стартует уже с правом, оставаясь «обычным» процессом.
# Лаунчер (focsq.sh) делает setcap на focsq-tun и запускает через неё.
add_executable(focsq-tun "focsq-tun.c")

# Apply the standard set of build settings. This can be removed for applications
# that need different build settings.
apply_standard_settings(${BINARY_NAME})

# Add preprocessor definitions for the application ID.
add_definitions(-DAPPLICATION_ID="${APPLICATION_ID}")

# Add dependency libraries. Add any application-specific dependencies here.
target_link_libraries(${BINARY_NAME} PRIVATE flutter)
target_link_libraries(${BINARY_NAME} PRIVATE PkgConfig::GTK)

target_include_directories(${BINARY_NAME} PRIVATE "${CMAKE_SOURCE_DIR}")
