// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import 'dart:async';
import 'dart:convert';
import 'dart:io';

import '../logs.dart';

class VkApiClient {
  VkApiClient({this.timeout = const Duration(seconds: 8)});

  static const String apiBase = 'https://api.vk.ru/method/';
  static const String apiVersion = '5.199';

  static const Set<int> tokenInvalidCodes = {4, 5, 27, 28};

  final Duration timeout;
  final HttpClient _client = HttpClient()
    ..connectionTimeout = const Duration(seconds: 8);

  Future<Map<String, dynamic>?> call(
    String method,
    String token,
    Map<String, String> params,
  ) async {
    final uri = Uri.parse('$apiBase$method');
    final body = [
      for (final entry in params.entries)
        '${Uri.encodeQueryComponent(entry.key)}='
            '${Uri.encodeQueryComponent(entry.value)}',
      'v=$apiVersion',
    ].join('&');
    try {
      final request = await _client.postUrl(uri).timeout(timeout);
      request.headers.set(HttpHeaders.authorizationHeader, 'Bearer $token');
      request.headers.set(
        HttpHeaders.contentTypeHeader,
        'application/x-www-form-urlencoded',
      );
      request.add(utf8.encode(body));
      final response = await request.close().timeout(timeout);
      final text = await response.transform(utf8.decoder).join();
      if (text.isEmpty) return null;
      final json = jsonDecode(text);
      return json is Map<String, dynamic> ? json : null;
    } catch (_) {
      return null;
    }
  }

  void close() {
    _client.close(force: true);
  }
}

sealed class VkCallStartResult {
  const VkCallStartResult();
}

class VkCallStarted extends VkCallStartResult {
  final String callId;
  final String hash;

  const VkCallStarted({required this.callId, required this.hash});
}

class VkCallStartApiError extends VkCallStartResult {
  final int code;
  final String message;

  const VkCallStartApiError(this.code, this.message);

  bool get tokenInvalid => VkApiClient.tokenInvalidCodes.contains(code);
}

class VkCallStartFailed extends VkCallStartResult {
  final String reason;

  const VkCallStartFailed(this.reason);
}

extension VkApiCalls on VkApiClient {

  Future<VkCallStartResult> startCall(String token) async {
    final json = await call('calls.start', token, const {});
    if (json == null) return const VkCallStartFailed('нет ответа VK API');

    final error = json['error'];
    if (error is Map<String, dynamic>) {
      return VkCallStartApiError(
        (error['error_code'] as num?)?.toInt() ?? 1,
        error['error_msg']?.toString() ?? '',
      );
    }

    final response = json['response'];
    if (response is! Map<String, dynamic>) {
      return const VkCallStartFailed('нет response в ответе calls.start');
    }

    final callId = response['call_id']?.toString() ?? '';
    final joinLink = response['join_link']?.toString() ?? '';
    final okJoinLink = response['ok_join_link']?.toString() ?? '';
    var hash = okJoinLink.trim();
    if (hash.isEmpty && joinLink.isNotEmpty) {
      final segments =
          joinLink.trim().split('/').where((s) => s.isNotEmpty).toList();
      hash = segments.isNotEmpty ? segments.last : '';
    }
    if (callId.isEmpty || hash.isEmpty) {
      return const VkCallStartFailed('пустой call_id/hash в ответе calls.start');
    }
    return VkCallStarted(callId: callId, hash: hash);
  }

  Future<bool> forceFinishCall(String token, String callId) async {
    final json = await call('calls.forceFinish', token, {'call_id': callId});
    if (json == null) return false;
    final error = json['error'];
    return error == null || error is! Map<String, dynamic>;
  }
}

class VkAutoCall {
  final String callId;
  final String hash;

  const VkAutoCall({required this.callId, required this.hash});
}

class VkCallsBatch {
  final List<VkAutoCall> calls;
  final int requestedCalls;

  const VkCallsBatch({required this.calls, required this.requestedCalls});

  List<String> get hashes => calls.map((c) => c.hash).toList();
  List<String> get callIds => calls.map((c) => c.callId).toList();

  bool get needsWorkerRedistribution => calls.length < requestedCalls;
}

class VkAutoCallsManager {
  VkAutoCallsManager({VkApiClient? client}) : _client = client ?? VkApiClient();

  static const int maxVkHashes = 6;
  static const int groupsPerHash = 3;
  static const int workersPerGroup = 9;
  static const int _maxAttemptsPerCall = 3;
  static const Duration _smallDelay = Duration(milliseconds: 80);
  static const Duration _largeDelay = Duration(milliseconds: 202);
  static const Duration _finishTimeout = Duration(seconds: 8);

  final VkApiClient _client;

  bool _tokenInvalidSeen = false;

  bool get tokenInvalidSeen => _tokenInvalidSeen;

  static int callCountForWorkers(int workers) {
    for (var n = 1; n <= maxVkHashes; n++) {
      if (workers <= n * groupsPerHash * workersPerGroup) return n;
    }
    return maxVkHashes;
  }

  Future<VkCallStartResult> _startCallWithAttempts(String token) async {
    VkCallStartResult last = const VkCallStartFailed('not attempted');
    for (var attempt = 0; attempt < _maxAttemptsPerCall; attempt++) {
      last = await _client.startCall(token);
      if (last is VkCallStarted) break;
      if (last is VkCallStartApiError && last.tokenInvalid) {
        break;
      }
    }
    return last;
  }

  Future<VkCallsBatch?> createForWorkers({
    required String token,
    required int workers,
    void Function(int created, int total)? onProgress,
  }) async {
    if (token.trim().isEmpty) return null;

    final count = callCountForWorkers(workers);
    final delay = count <= 4 ? _smallDelay : _largeDelay;
    final calls = <VkAutoCall>[];
    _tokenInvalidSeen = false;

    for (var slot = 0; slot < count; slot++) {
      if (slot > 0) await Future<void>.delayed(delay);

      var tokenInvalid = false;
      final result = await _startCallWithAttempts(token);
      switch (result) {
        case final VkCallStarted started:
          calls.add(
            VkAutoCall(callId: started.callId, hash: started.hash),
          );
        case final VkCallStartApiError error:
          LogService().add(
            '[АВТО API] Звонок не создан · код=${error.code} ${error.message}',
          );
          tokenInvalid = error.tokenInvalid;
          if (tokenInvalid) _tokenInvalidSeen = true;
        case final VkCallStartFailed failure:
          LogService().add('[АВТО API] Звонок не создан · ${failure.reason}');
      }
      onProgress?.call(calls.length, count);
      if (tokenInvalid) {
        LogService().add('[АВТО API] Токен недействителен · войдите снова');
        return null;
      }
    }

    if (calls.isEmpty) return null;
    if (calls.length < count) {
      LogService().add(
        '[АВТО API] Звонки ${calls.length}/$count · потоки распределены',
      );
    }
    return VkCallsBatch(calls: calls, requestedCalls: count);
  }

  Future<int> finishAll(String token, List<String> callIds) async {
    if (token.trim().isEmpty || callIds.isEmpty) return 0;
    var finished = 0;
    try {
      await Future.wait(callIds.map((callId) async {
        try {
          if (await _client
              .forceFinishCall(token, callId)
              .timeout(_finishTimeout)) {
            finished++;
          }
        } catch (_) {}
      }));
    } catch (_) {}
    LogService().add('[АВТО API] Звонки завершены $finished/${callIds.length}');
    return finished;
  }

  void close() => _client.close();
}