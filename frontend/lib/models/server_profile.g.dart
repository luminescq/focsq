// GENERATED CODE - DO NOT MODIFY BY HAND

part of 'server_profile.dart';

// **************************************************************************
// IsarCollectionGenerator
// **************************************************************************

// coverage:ignore-file
// ignore_for_file: duplicate_ignore, non_constant_identifier_names, constant_identifier_names, invalid_use_of_protected_member, unnecessary_cast, prefer_const_constructors, lines_longer_than_80_chars, require_trailing_commas, inference_failure_on_function_invocation, unnecessary_parenthesis, unnecessary_raw_strings, unnecessary_null_checks, join_return_with_assignment, prefer_final_locals, avoid_js_rounded_ints, avoid_positional_boolean_parameters, always_specify_types

extension GetServerProfileCollection on Isar {
  IsarCollection<ServerProfile> get serverProfiles => this.collection();
}

const ServerProfileSchema = CollectionSchema(
  name: r'ServerProfile',
  id: 121119387985718548,
  properties: {
    r'ip': PropertySchema(id: 0, name: r'ip', type: IsarType.string),
    r'name': PropertySchema(id: 1, name: r'name', type: IsarType.string),
    r'password': PropertySchema(
      id: 2,
      name: r'password',
      type: IsarType.string,
    ),
    r'port': PropertySchema(id: 3, name: r'port', type: IsarType.long),
    r'power': PropertySchema(id: 4, name: r'power', type: IsarType.long),
  },
  estimateSize: _serverProfileEstimateSize,
  serialize: _serverProfileSerialize,
  deserialize: _serverProfileDeserialize,
  deserializeProp: _serverProfileDeserializeProp,
  idName: r'id',
  indexes: {},
  links: {},
  embeddedSchemas: {},
  getId: _serverProfileGetId,
  getLinks: _serverProfileGetLinks,
  attach: _serverProfileAttach,
  version: '3.1.0+1',
);

int _serverProfileEstimateSize(
  ServerProfile object,
  List<int> offsets,
  Map<Type, List<int>> allOffsets,
) {
  var bytesCount = offsets.last;
  {
    final value = object.ip;
    if (value != null) {
      bytesCount += 3 + value.length * 3;
    }
  }
  {
    final value = object.name;
    if (value != null) {
      bytesCount += 3 + value.length * 3;
    }
  }
  {
    final value = object.password;
    if (value != null) {
      bytesCount += 3 + value.length * 3;
    }
  }
  return bytesCount;
}

void _serverProfileSerialize(
  ServerProfile object,
  IsarWriter writer,
  List<int> offsets,
  Map<Type, List<int>> allOffsets,
) {
  writer.writeString(offsets[0], object.ip);
  writer.writeString(offsets[1], object.name);
  writer.writeString(offsets[2], object.password);
  writer.writeLong(offsets[3], object.port);
  writer.writeLong(offsets[4], object.power);
}

ServerProfile _serverProfileDeserialize(
  Id id,
  IsarReader reader,
  List<int> offsets,
  Map<Type, List<int>> allOffsets,
) {
  final object = ServerProfile(
    id: id,
    ip: reader.readStringOrNull(offsets[0]),
    name: reader.readStringOrNull(offsets[1]),
    password: reader.readStringOrNull(offsets[2]),
    port: reader.readLongOrNull(offsets[3]),
    power: reader.readLongOrNull(offsets[4]),
  );
  return object;
}

P _serverProfileDeserializeProp<P>(
  IsarReader reader,
  int propertyId,
  int offset,
  Map<Type, List<int>> allOffsets,
) {
  switch (propertyId) {
    case 0:
      return (reader.readStringOrNull(offset)) as P;
    case 1:
      return (reader.readStringOrNull(offset)) as P;
    case 2:
      return (reader.readStringOrNull(offset)) as P;
    case 3:
      return (reader.readLongOrNull(offset)) as P;
    case 4:
      return (reader.readLongOrNull(offset)) as P;
    default:
      throw IsarError('Unknown property with id $propertyId');
  }
}

Id _serverProfileGetId(ServerProfile object) {
  return object.id;
}

List<IsarLinkBase<dynamic>> _serverProfileGetLinks(ServerProfile object) {
  return [];
}

void _serverProfileAttach(
  IsarCollection<dynamic> col,
  Id id,
  ServerProfile object,
) {
  object.id = id;
}

extension ServerProfileQueryWhereSort
    on QueryBuilder<ServerProfile, ServerProfile, QWhere> {
  QueryBuilder<ServerProfile, ServerProfile, QAfterWhere> anyId() {
    return QueryBuilder.apply(this, (query) {
      return query.addWhereClause(const IdWhereClause.any());
    });
  }
}

extension ServerProfileQueryWhere
    on QueryBuilder<ServerProfile, ServerProfile, QWhereClause> {
  QueryBuilder<ServerProfile, ServerProfile, QAfterWhereClause> idEqualTo(
    Id id,
  ) {
    return QueryBuilder.apply(this, (query) {
      return query.addWhereClause(IdWhereClause.between(lower: id, upper: id));
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterWhereClause> idNotEqualTo(
    Id id,
  ) {
    return QueryBuilder.apply(this, (query) {
      if (query.whereSort == Sort.asc) {
        return query
            .addWhereClause(
              IdWhereClause.lessThan(upper: id, includeUpper: false),
            )
            .addWhereClause(
              IdWhereClause.greaterThan(lower: id, includeLower: false),
            );
      } else {
        return query
            .addWhereClause(
              IdWhereClause.greaterThan(lower: id, includeLower: false),
            )
            .addWhereClause(
              IdWhereClause.lessThan(upper: id, includeUpper: false),
            );
      }
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterWhereClause> idGreaterThan(
    Id id, {
    bool include = false,
  }) {
    return QueryBuilder.apply(this, (query) {
      return query.addWhereClause(
        IdWhereClause.greaterThan(lower: id, includeLower: include),
      );
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterWhereClause> idLessThan(
    Id id, {
    bool include = false,
  }) {
    return QueryBuilder.apply(this, (query) {
      return query.addWhereClause(
        IdWhereClause.lessThan(upper: id, includeUpper: include),
      );
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterWhereClause> idBetween(
    Id lowerId,
    Id upperId, {
    bool includeLower = true,
    bool includeUpper = true,
  }) {
    return QueryBuilder.apply(this, (query) {
      return query.addWhereClause(
        IdWhereClause.between(
          lower: lowerId,
          includeLower: includeLower,
          upper: upperId,
          includeUpper: includeUpper,
        ),
      );
    });
  }
}

extension ServerProfileQueryFilter
    on QueryBuilder<ServerProfile, ServerProfile, QFilterCondition> {
  QueryBuilder<ServerProfile, ServerProfile, QAfterFilterCondition> idEqualTo(
    Id value,
  ) {
    return QueryBuilder.apply(this, (query) {
      return query.addFilterCondition(
        FilterCondition.equalTo(property: r'id', value: value),
      );
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterFilterCondition>
  idGreaterThan(Id value, {bool include = false}) {
    return QueryBuilder.apply(this, (query) {
      return query.addFilterCondition(
        FilterCondition.greaterThan(
          include: include,
          property: r'id',
          value: value,
        ),
      );
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterFilterCondition> idLessThan(
    Id value, {
    bool include = false,
  }) {
    return QueryBuilder.apply(this, (query) {
      return query.addFilterCondition(
        FilterCondition.lessThan(
          include: include,
          property: r'id',
          value: value,
        ),
      );
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterFilterCondition> idBetween(
    Id lower,
    Id upper, {
    bool includeLower = true,
    bool includeUpper = true,
  }) {
    return QueryBuilder.apply(this, (query) {
      return query.addFilterCondition(
        FilterCondition.between(
          property: r'id',
          lower: lower,
          includeLower: includeLower,
          upper: upper,
          includeUpper: includeUpper,
        ),
      );
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterFilterCondition> ipIsNull() {
    return QueryBuilder.apply(this, (query) {
      return query.addFilterCondition(
        const FilterCondition.isNull(property: r'ip'),
      );
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterFilterCondition>
  ipIsNotNull() {
    return QueryBuilder.apply(this, (query) {
      return query.addFilterCondition(
        const FilterCondition.isNotNull(property: r'ip'),
      );
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterFilterCondition> ipEqualTo(
    String? value, {
    bool caseSensitive = true,
  }) {
    return QueryBuilder.apply(this, (query) {
      return query.addFilterCondition(
        FilterCondition.equalTo(
          property: r'ip',
          value: value,
          caseSensitive: caseSensitive,
        ),
      );
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterFilterCondition>
  ipGreaterThan(
    String? value, {
    bool include = false,
    bool caseSensitive = true,
  }) {
    return QueryBuilder.apply(this, (query) {
      return query.addFilterCondition(
        FilterCondition.greaterThan(
          include: include,
          property: r'ip',
          value: value,
          caseSensitive: caseSensitive,
        ),
      );
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterFilterCondition> ipLessThan(
    String? value, {
    bool include = false,
    bool caseSensitive = true,
  }) {
    return QueryBuilder.apply(this, (query) {
      return query.addFilterCondition(
        FilterCondition.lessThan(
          include: include,
          property: r'ip',
          value: value,
          caseSensitive: caseSensitive,
        ),
      );
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterFilterCondition> ipBetween(
    String? lower,
    String? upper, {
    bool includeLower = true,
    bool includeUpper = true,
    bool caseSensitive = true,
  }) {
    return QueryBuilder.apply(this, (query) {
      return query.addFilterCondition(
        FilterCondition.between(
          property: r'ip',
          lower: lower,
          includeLower: includeLower,
          upper: upper,
          includeUpper: includeUpper,
          caseSensitive: caseSensitive,
        ),
      );
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterFilterCondition>
  ipStartsWith(String value, {bool caseSensitive = true}) {
    return QueryBuilder.apply(this, (query) {
      return query.addFilterCondition(
        FilterCondition.startsWith(
          property: r'ip',
          value: value,
          caseSensitive: caseSensitive,
        ),
      );
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterFilterCondition> ipEndsWith(
    String value, {
    bool caseSensitive = true,
  }) {
    return QueryBuilder.apply(this, (query) {
      return query.addFilterCondition(
        FilterCondition.endsWith(
          property: r'ip',
          value: value,
          caseSensitive: caseSensitive,
        ),
      );
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterFilterCondition> ipContains(
    String value, {
    bool caseSensitive = true,
  }) {
    return QueryBuilder.apply(this, (query) {
      return query.addFilterCondition(
        FilterCondition.contains(
          property: r'ip',
          value: value,
          caseSensitive: caseSensitive,
        ),
      );
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterFilterCondition> ipMatches(
    String pattern, {
    bool caseSensitive = true,
  }) {
    return QueryBuilder.apply(this, (query) {
      return query.addFilterCondition(
        FilterCondition.matches(
          property: r'ip',
          wildcard: pattern,
          caseSensitive: caseSensitive,
        ),
      );
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterFilterCondition>
  ipIsEmpty() {
    return QueryBuilder.apply(this, (query) {
      return query.addFilterCondition(
        FilterCondition.equalTo(property: r'ip', value: ''),
      );
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterFilterCondition>
  ipIsNotEmpty() {
    return QueryBuilder.apply(this, (query) {
      return query.addFilterCondition(
        FilterCondition.greaterThan(property: r'ip', value: ''),
      );
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterFilterCondition>
  nameIsNull() {
    return QueryBuilder.apply(this, (query) {
      return query.addFilterCondition(
        const FilterCondition.isNull(property: r'name'),
      );
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterFilterCondition>
  nameIsNotNull() {
    return QueryBuilder.apply(this, (query) {
      return query.addFilterCondition(
        const FilterCondition.isNotNull(property: r'name'),
      );
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterFilterCondition> nameEqualTo(
    String? value, {
    bool caseSensitive = true,
  }) {
    return QueryBuilder.apply(this, (query) {
      return query.addFilterCondition(
        FilterCondition.equalTo(
          property: r'name',
          value: value,
          caseSensitive: caseSensitive,
        ),
      );
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterFilterCondition>
  nameGreaterThan(
    String? value, {
    bool include = false,
    bool caseSensitive = true,
  }) {
    return QueryBuilder.apply(this, (query) {
      return query.addFilterCondition(
        FilterCondition.greaterThan(
          include: include,
          property: r'name',
          value: value,
          caseSensitive: caseSensitive,
        ),
      );
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterFilterCondition>
  nameLessThan(
    String? value, {
    bool include = false,
    bool caseSensitive = true,
  }) {
    return QueryBuilder.apply(this, (query) {
      return query.addFilterCondition(
        FilterCondition.lessThan(
          include: include,
          property: r'name',
          value: value,
          caseSensitive: caseSensitive,
        ),
      );
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterFilterCondition> nameBetween(
    String? lower,
    String? upper, {
    bool includeLower = true,
    bool includeUpper = true,
    bool caseSensitive = true,
  }) {
    return QueryBuilder.apply(this, (query) {
      return query.addFilterCondition(
        FilterCondition.between(
          property: r'name',
          lower: lower,
          includeLower: includeLower,
          upper: upper,
          includeUpper: includeUpper,
          caseSensitive: caseSensitive,
        ),
      );
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterFilterCondition>
  nameStartsWith(String value, {bool caseSensitive = true}) {
    return QueryBuilder.apply(this, (query) {
      return query.addFilterCondition(
        FilterCondition.startsWith(
          property: r'name',
          value: value,
          caseSensitive: caseSensitive,
        ),
      );
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterFilterCondition>
  nameEndsWith(String value, {bool caseSensitive = true}) {
    return QueryBuilder.apply(this, (query) {
      return query.addFilterCondition(
        FilterCondition.endsWith(
          property: r'name',
          value: value,
          caseSensitive: caseSensitive,
        ),
      );
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterFilterCondition>
  nameContains(String value, {bool caseSensitive = true}) {
    return QueryBuilder.apply(this, (query) {
      return query.addFilterCondition(
        FilterCondition.contains(
          property: r'name',
          value: value,
          caseSensitive: caseSensitive,
        ),
      );
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterFilterCondition> nameMatches(
    String pattern, {
    bool caseSensitive = true,
  }) {
    return QueryBuilder.apply(this, (query) {
      return query.addFilterCondition(
        FilterCondition.matches(
          property: r'name',
          wildcard: pattern,
          caseSensitive: caseSensitive,
        ),
      );
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterFilterCondition>
  nameIsEmpty() {
    return QueryBuilder.apply(this, (query) {
      return query.addFilterCondition(
        FilterCondition.equalTo(property: r'name', value: ''),
      );
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterFilterCondition>
  nameIsNotEmpty() {
    return QueryBuilder.apply(this, (query) {
      return query.addFilterCondition(
        FilterCondition.greaterThan(property: r'name', value: ''),
      );
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterFilterCondition>
  passwordIsNull() {
    return QueryBuilder.apply(this, (query) {
      return query.addFilterCondition(
        const FilterCondition.isNull(property: r'password'),
      );
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterFilterCondition>
  passwordIsNotNull() {
    return QueryBuilder.apply(this, (query) {
      return query.addFilterCondition(
        const FilterCondition.isNotNull(property: r'password'),
      );
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterFilterCondition>
  passwordEqualTo(String? value, {bool caseSensitive = true}) {
    return QueryBuilder.apply(this, (query) {
      return query.addFilterCondition(
        FilterCondition.equalTo(
          property: r'password',
          value: value,
          caseSensitive: caseSensitive,
        ),
      );
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterFilterCondition>
  passwordGreaterThan(
    String? value, {
    bool include = false,
    bool caseSensitive = true,
  }) {
    return QueryBuilder.apply(this, (query) {
      return query.addFilterCondition(
        FilterCondition.greaterThan(
          include: include,
          property: r'password',
          value: value,
          caseSensitive: caseSensitive,
        ),
      );
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterFilterCondition>
  passwordLessThan(
    String? value, {
    bool include = false,
    bool caseSensitive = true,
  }) {
    return QueryBuilder.apply(this, (query) {
      return query.addFilterCondition(
        FilterCondition.lessThan(
          include: include,
          property: r'password',
          value: value,
          caseSensitive: caseSensitive,
        ),
      );
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterFilterCondition>
  passwordBetween(
    String? lower,
    String? upper, {
    bool includeLower = true,
    bool includeUpper = true,
    bool caseSensitive = true,
  }) {
    return QueryBuilder.apply(this, (query) {
      return query.addFilterCondition(
        FilterCondition.between(
          property: r'password',
          lower: lower,
          includeLower: includeLower,
          upper: upper,
          includeUpper: includeUpper,
          caseSensitive: caseSensitive,
        ),
      );
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterFilterCondition>
  passwordStartsWith(String value, {bool caseSensitive = true}) {
    return QueryBuilder.apply(this, (query) {
      return query.addFilterCondition(
        FilterCondition.startsWith(
          property: r'password',
          value: value,
          caseSensitive: caseSensitive,
        ),
      );
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterFilterCondition>
  passwordEndsWith(String value, {bool caseSensitive = true}) {
    return QueryBuilder.apply(this, (query) {
      return query.addFilterCondition(
        FilterCondition.endsWith(
          property: r'password',
          value: value,
          caseSensitive: caseSensitive,
        ),
      );
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterFilterCondition>
  passwordContains(String value, {bool caseSensitive = true}) {
    return QueryBuilder.apply(this, (query) {
      return query.addFilterCondition(
        FilterCondition.contains(
          property: r'password',
          value: value,
          caseSensitive: caseSensitive,
        ),
      );
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterFilterCondition>
  passwordMatches(String pattern, {bool caseSensitive = true}) {
    return QueryBuilder.apply(this, (query) {
      return query.addFilterCondition(
        FilterCondition.matches(
          property: r'password',
          wildcard: pattern,
          caseSensitive: caseSensitive,
        ),
      );
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterFilterCondition>
  passwordIsEmpty() {
    return QueryBuilder.apply(this, (query) {
      return query.addFilterCondition(
        FilterCondition.equalTo(property: r'password', value: ''),
      );
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterFilterCondition>
  passwordIsNotEmpty() {
    return QueryBuilder.apply(this, (query) {
      return query.addFilterCondition(
        FilterCondition.greaterThan(property: r'password', value: ''),
      );
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterFilterCondition>
  portIsNull() {
    return QueryBuilder.apply(this, (query) {
      return query.addFilterCondition(
        const FilterCondition.isNull(property: r'port'),
      );
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterFilterCondition>
  portIsNotNull() {
    return QueryBuilder.apply(this, (query) {
      return query.addFilterCondition(
        const FilterCondition.isNotNull(property: r'port'),
      );
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterFilterCondition> portEqualTo(
    int? value,
  ) {
    return QueryBuilder.apply(this, (query) {
      return query.addFilterCondition(
        FilterCondition.equalTo(property: r'port', value: value),
      );
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterFilterCondition>
  portGreaterThan(int? value, {bool include = false}) {
    return QueryBuilder.apply(this, (query) {
      return query.addFilterCondition(
        FilterCondition.greaterThan(
          include: include,
          property: r'port',
          value: value,
        ),
      );
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterFilterCondition>
  portLessThan(int? value, {bool include = false}) {
    return QueryBuilder.apply(this, (query) {
      return query.addFilterCondition(
        FilterCondition.lessThan(
          include: include,
          property: r'port',
          value: value,
        ),
      );
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterFilterCondition> portBetween(
    int? lower,
    int? upper, {
    bool includeLower = true,
    bool includeUpper = true,
  }) {
    return QueryBuilder.apply(this, (query) {
      return query.addFilterCondition(
        FilterCondition.between(
          property: r'port',
          lower: lower,
          includeLower: includeLower,
          upper: upper,
          includeUpper: includeUpper,
        ),
      );
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterFilterCondition>
  powerIsNull() {
    return QueryBuilder.apply(this, (query) {
      return query.addFilterCondition(
        const FilterCondition.isNull(property: r'power'),
      );
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterFilterCondition>
  powerIsNotNull() {
    return QueryBuilder.apply(this, (query) {
      return query.addFilterCondition(
        const FilterCondition.isNotNull(property: r'power'),
      );
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterFilterCondition>
  powerEqualTo(int? value) {
    return QueryBuilder.apply(this, (query) {
      return query.addFilterCondition(
        FilterCondition.equalTo(property: r'power', value: value),
      );
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterFilterCondition>
  powerGreaterThan(int? value, {bool include = false}) {
    return QueryBuilder.apply(this, (query) {
      return query.addFilterCondition(
        FilterCondition.greaterThan(
          include: include,
          property: r'power',
          value: value,
        ),
      );
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterFilterCondition>
  powerLessThan(int? value, {bool include = false}) {
    return QueryBuilder.apply(this, (query) {
      return query.addFilterCondition(
        FilterCondition.lessThan(
          include: include,
          property: r'power',
          value: value,
        ),
      );
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterFilterCondition>
  powerBetween(
    int? lower,
    int? upper, {
    bool includeLower = true,
    bool includeUpper = true,
  }) {
    return QueryBuilder.apply(this, (query) {
      return query.addFilterCondition(
        FilterCondition.between(
          property: r'power',
          lower: lower,
          includeLower: includeLower,
          upper: upper,
          includeUpper: includeUpper,
        ),
      );
    });
  }
}

extension ServerProfileQueryObject
    on QueryBuilder<ServerProfile, ServerProfile, QFilterCondition> {}

extension ServerProfileQueryLinks
    on QueryBuilder<ServerProfile, ServerProfile, QFilterCondition> {}

extension ServerProfileQuerySortBy
    on QueryBuilder<ServerProfile, ServerProfile, QSortBy> {
  QueryBuilder<ServerProfile, ServerProfile, QAfterSortBy> sortByIp() {
    return QueryBuilder.apply(this, (query) {
      return query.addSortBy(r'ip', Sort.asc);
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterSortBy> sortByIpDesc() {
    return QueryBuilder.apply(this, (query) {
      return query.addSortBy(r'ip', Sort.desc);
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterSortBy> sortByName() {
    return QueryBuilder.apply(this, (query) {
      return query.addSortBy(r'name', Sort.asc);
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterSortBy> sortByNameDesc() {
    return QueryBuilder.apply(this, (query) {
      return query.addSortBy(r'name', Sort.desc);
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterSortBy> sortByPassword() {
    return QueryBuilder.apply(this, (query) {
      return query.addSortBy(r'password', Sort.asc);
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterSortBy>
  sortByPasswordDesc() {
    return QueryBuilder.apply(this, (query) {
      return query.addSortBy(r'password', Sort.desc);
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterSortBy> sortByPort() {
    return QueryBuilder.apply(this, (query) {
      return query.addSortBy(r'port', Sort.asc);
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterSortBy> sortByPortDesc() {
    return QueryBuilder.apply(this, (query) {
      return query.addSortBy(r'port', Sort.desc);
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterSortBy> sortByPower() {
    return QueryBuilder.apply(this, (query) {
      return query.addSortBy(r'power', Sort.asc);
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterSortBy> sortByPowerDesc() {
    return QueryBuilder.apply(this, (query) {
      return query.addSortBy(r'power', Sort.desc);
    });
  }
}

extension ServerProfileQuerySortThenBy
    on QueryBuilder<ServerProfile, ServerProfile, QSortThenBy> {
  QueryBuilder<ServerProfile, ServerProfile, QAfterSortBy> thenById() {
    return QueryBuilder.apply(this, (query) {
      return query.addSortBy(r'id', Sort.asc);
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterSortBy> thenByIdDesc() {
    return QueryBuilder.apply(this, (query) {
      return query.addSortBy(r'id', Sort.desc);
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterSortBy> thenByIp() {
    return QueryBuilder.apply(this, (query) {
      return query.addSortBy(r'ip', Sort.asc);
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterSortBy> thenByIpDesc() {
    return QueryBuilder.apply(this, (query) {
      return query.addSortBy(r'ip', Sort.desc);
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterSortBy> thenByName() {
    return QueryBuilder.apply(this, (query) {
      return query.addSortBy(r'name', Sort.asc);
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterSortBy> thenByNameDesc() {
    return QueryBuilder.apply(this, (query) {
      return query.addSortBy(r'name', Sort.desc);
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterSortBy> thenByPassword() {
    return QueryBuilder.apply(this, (query) {
      return query.addSortBy(r'password', Sort.asc);
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterSortBy>
  thenByPasswordDesc() {
    return QueryBuilder.apply(this, (query) {
      return query.addSortBy(r'password', Sort.desc);
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterSortBy> thenByPort() {
    return QueryBuilder.apply(this, (query) {
      return query.addSortBy(r'port', Sort.asc);
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterSortBy> thenByPortDesc() {
    return QueryBuilder.apply(this, (query) {
      return query.addSortBy(r'port', Sort.desc);
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterSortBy> thenByPower() {
    return QueryBuilder.apply(this, (query) {
      return query.addSortBy(r'power', Sort.asc);
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QAfterSortBy> thenByPowerDesc() {
    return QueryBuilder.apply(this, (query) {
      return query.addSortBy(r'power', Sort.desc);
    });
  }
}

extension ServerProfileQueryWhereDistinct
    on QueryBuilder<ServerProfile, ServerProfile, QDistinct> {
  QueryBuilder<ServerProfile, ServerProfile, QDistinct> distinctByIp({
    bool caseSensitive = true,
  }) {
    return QueryBuilder.apply(this, (query) {
      return query.addDistinctBy(r'ip', caseSensitive: caseSensitive);
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QDistinct> distinctByName({
    bool caseSensitive = true,
  }) {
    return QueryBuilder.apply(this, (query) {
      return query.addDistinctBy(r'name', caseSensitive: caseSensitive);
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QDistinct> distinctByPassword({
    bool caseSensitive = true,
  }) {
    return QueryBuilder.apply(this, (query) {
      return query.addDistinctBy(r'password', caseSensitive: caseSensitive);
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QDistinct> distinctByPort() {
    return QueryBuilder.apply(this, (query) {
      return query.addDistinctBy(r'port');
    });
  }

  QueryBuilder<ServerProfile, ServerProfile, QDistinct> distinctByPower() {
    return QueryBuilder.apply(this, (query) {
      return query.addDistinctBy(r'power');
    });
  }
}

extension ServerProfileQueryProperty
    on QueryBuilder<ServerProfile, ServerProfile, QQueryProperty> {
  QueryBuilder<ServerProfile, int, QQueryOperations> idProperty() {
    return QueryBuilder.apply(this, (query) {
      return query.addPropertyName(r'id');
    });
  }

  QueryBuilder<ServerProfile, String?, QQueryOperations> ipProperty() {
    return QueryBuilder.apply(this, (query) {
      return query.addPropertyName(r'ip');
    });
  }

  QueryBuilder<ServerProfile, String?, QQueryOperations> nameProperty() {
    return QueryBuilder.apply(this, (query) {
      return query.addPropertyName(r'name');
    });
  }

  QueryBuilder<ServerProfile, String?, QQueryOperations> passwordProperty() {
    return QueryBuilder.apply(this, (query) {
      return query.addPropertyName(r'password');
    });
  }

  QueryBuilder<ServerProfile, int?, QQueryOperations> portProperty() {
    return QueryBuilder.apply(this, (query) {
      return query.addPropertyName(r'port');
    });
  }

  QueryBuilder<ServerProfile, int?, QQueryOperations> powerProperty() {
    return QueryBuilder.apply(this, (query) {
      return query.addPropertyName(r'power');
    });
  }
}
