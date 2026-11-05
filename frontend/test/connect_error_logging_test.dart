import 'package:flutter_test/flutter_test.dart';
import 'package:frontend/service/connect.dart';
import 'package:frontend/service/logs.dart';

void main() {
  setUp(() {
    LogService.developerMode = false;
    LogService().clear();
  });

  test(
    'named backend error code survives decoding and reaches the journal',
    () {
      ConnectService().onCoreLine(
        '__CSQTT_EVENT__|ERROR|{"code":"handshake_timeout",'
        '"message":"No configuration reply", "fatal":false}',
      );
      expect(LogService().lines.join('\n'), contains('(handshake_timeout)'));
      expect(LogService().lines.join('\n'), contains('No configuration reply'));
    },
  );

  test('numeric legacy backend error codes still reach the journal', () {
    ConnectService().onCoreLine(
      '__CSQTT_EVENT__|ERROR|{"code":403,"message":"Denied", "fatal":false}',
    );
    expect(LogService().lines.join('\n'), contains('(403)'));
  });
}
