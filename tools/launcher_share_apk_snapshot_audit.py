#!/usr/bin/env python3
"""顺序解码 Dart isolate 快照的对象池四槽；只读，不连接设备或加载目标库。"""

import argparse
import json
import struct
import sys
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from launcher_native_audit import AuditError, ElfImage, digest, number, require  # noqa: E402


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CONTRACT = ROOT / "app/src/test/resources/launcher-share-apk-snapshot-pool-contract.json"
POLICY = "share-apk-snapshot-pool-offline-v1"
HOST_VERSION = "5d85d85296400e9b3992193deed317c9"
OFFICIAL_VERSION = "1ce86630892e2dca9a8543fdb8ed8e22"
FEATURES = ("product no-code_comments dwarf_stack_traces_mode dedup_instructions "
            "no-tsan no-msan no-shared_data arm64 android compressed-pointers")
MAGIC = 0xDCDCF5F5
KIND_FULL_AOT = 3
EXPECTED_SNAPSHOT_LENGTH = 6457283
EXPECTED_HEADER = (1181, 277591, 1465, 12607, 64)
EXPECTED_POOL_LENGTH = 123596
EXPECTED_CANONICAL_STRINGS = 117870
EXPECTED_ENTRY_COUNTS = {"obj": 121933, "imm": 797, "skip": 683, "native": 183}
EXPECTED_SYMBOLS = {
    "_kDartVmSnapshotData": (0x340, 18256),
    "_kDartIsolateSnapshotData": (0x4AC0, 8681712),
}
SLOT_SPECS = (
    ("firstKey", 0xB9458, 94857, "android.intent.extra.PACKAGE_NAME"),
    ("secondKey", 0xB9460, 94858, "com.miui.mishare.extra.MISHARE_APK_PACKAGE_NAME"),
    ("firstField", 0xB9468, 94859, "android.intent.action.SEND"),
    ("lastField", 0xB9470, 94860, "application/vnd.android.package-archive"),
)
BOUNDARY = ("仅形成宿主静态快照字节的只读自洽证据；官方 tag 3.10.1 格式兼容未证，"
            "不覆盖实机运行期、线程时序、真实 Intent 组装、chooser、文件授权或分享可用。")


CID_NAMES = {
    5: "Class", 6: "PatchClass", 7: "Function", 8: "TypeParameters",
    9: "ClosureData", 10: "FfiTrampolineData", 11: "Field", 12: "Script",
    13: "Library", 17: "WeakArray", 18: "Code", 23: "ObjectPool",
    24: "PcDescriptors", 28: "ExceptionHandlers", 35: "UnlinkedCall",
    38: "SubtypeTestCache", 39: "LoadingUnit", 45: "Instance",
    47: "TypeArguments", 49: "Type", 50: "FunctionType", 51: "RecordType",
    52: "TypeParameter", 57: "Closure", 61: "Mint", 62: "Double",
    67: "Record", 87: "ConstMap", 89: "ConstSet", 90: "Array",
    91: "ImmutableArray", 92: "GrowableObjectArray", 93: "String",
    112: "TypedDataInt8Array", 132: "TypedDataInt32Array",
    136: "TypedDataUint32Array",
}
FIXED_ALLOC = {
    "Type", "FunctionType", "RecordType", "TypeParameter", "TypeParameters",
    "PatchClass", "Function", "ClosureData", "FfiTrampolineData", "Field",
    "Script", "Library", "Closure", "Double", "GrowableObjectArray",
    "UnlinkedCall", "SubtypeTestCache", "LoadingUnit", "ConstMap", "ConstSet",
}
VARIABLE_LENGTH_ALLOC = {
    "TypeArguments", "Array", "ImmutableArray", "WeakArray", "ObjectPool",
    "PcDescriptors", "ExceptionHandlers", "Record", "TypedDataInt8Array",
    "TypedDataInt32Array", "TypedDataUint32Array",
}
CANONICAL_LAYOUT = {
    "String", "TypeArguments", "Type", "FunctionType", "RecordType", "TypeParameter",
}
TYPED_DATA_SIZE = {112: 1, 132: 4, 136: 4}
READ_FROM_TO = {
    "TypeParameter": 3, "Type": 3, "FunctionType": 6, "RecordType": 4,
    "Closure": 6, "ConstMap": 5, "ConstSet": 5, "GrowableObjectArray": 3,
    "TypeParameters": 4, "PatchClass": 2, "Function": 4,
    "FfiTrampolineData": 4, "Field": 4, "Script": 1, "Library": 10,
    "UnlinkedCall": 2,
}


class Reader:
    """实现 Dart `ReadStream` 在本快照用到的三类整数读取。"""

    def __init__(self, data, start=0, end=None):
        self.data = data
        self.pos = start
        self.end = len(data) if end is None else end
        require(0 <= start <= self.end <= len(data), "快照读取区间越界")
        self.num_objects = None
        self.min_ref = None
        self.max_ref = None

    def take(self, size, label="快照数据"):
        require(isinstance(size, int) and size >= 0 and self.pos + size <= self.end,
                "%s被截断" % label)
        result = self.data[self.pos:self.pos + size]
        self.pos += size
        return result

    def byte(self, label="单字节字段"):
        return self.take(1, label)[0]

    def read_unsigned(self):
        return self._read_varint(128)

    def read_fixed(self):
        return self._read_varint(192)

    def _read_varint(self, marker):
        value = 0
        shift = 0
        while True:
            byte = self.byte("变长整数")
            if byte > 127:
                return value | ((byte - marker) << shift)
            value |= byte << shift
            shift += 7
            require(shift < 70, "变长整数超过 64 位边界")

    def read_ref(self):
        value = 0
        for _ in range(4):
            byte = self.byte("引用号")
            value = (value << 7) | (byte & 127)
            if byte > 127:
                require(self.num_objects is not None, "读取引用前未建立对象上界")
                require(1 <= value <= self.num_objects,
                        "填充引用号越界：%d 不在 1..%d" % (value, self.num_objects))
                self.min_ref = value if self.min_ref is None else min(self.min_ref, value)
                self.max_ref = value if self.max_ref is None else max(self.max_ref, value)
                return value
        raise AuditError("引用号超过四字节")


def dynamic_symbols(image):
    """只从 `.dynsym` 取符号；测试替身也必须给出同结构的动态符号。"""
    if hasattr(image, "dynamic_symbols"):
        return image.dynamic_symbols()
    symbols = image.section(".dynsym")
    require(symbols["type"] == 11 and symbols["entry_size"] == 24
            and symbols["size"] % 24 == 0, ".dynsym 格式不符")
    require(symbols["link"] < len(image.sections), ".dynsym 字符串表索引越界")
    strings = image.sections[symbols["link"]]
    require(strings["type"] == 3, ".dynsym 关联项不是字符串表")
    table = image.section_data(strings)
    result = []
    for offset in range(symbols["offset"], symbols["offset"] + symbols["size"], 24):
        name, info, other, section, value, size = struct.unpack_from("<IBBHQQ", image.data, offset)
        result.append({"name": image.string(table, name), "info": info, "other": other,
                       "section": section, "value": value, "size": size})
    return result


def unique_symbol(image, name):
    matches = [item for item in dynamic_symbols(image) if item["name"] == name]
    require(len(matches) == 1, "动态符号必须唯一：%s" % name)
    symbol = matches[0]
    require(symbol["section"] != 0 and symbol["size"] > 0, "动态符号未定义或大小为空：%s" % name)
    return symbol


def c_string(data, start, end, label):
    require(0 <= start < end <= len(data), "%s起点越界" % label)
    stop = data.find(b"\0", start, end)
    require(stop >= 0, "%s缺少 NUL 结束符" % label)
    try:
        value = data[start:stop].decode("utf-8", errors="strict")
    except UnicodeDecodeError as error:
        raise AuditError("%s不是严格 UTF-8：%s" % (label, error)) from error
    return value, stop + 1


def cluster_name(cid):
    if cid >= 175 or cid == 45:
        return "Instance"
    require(cid in CID_NAMES, "遇到未知 cid：%d" % cid)
    return CID_NAMES[cid]


def read_canonical_layout(reader, cluster):
    if not cluster["canonical"]:
        return
    table_length = reader.read_unsigned()
    first = reader.read_unsigned()
    require(first <= cluster["count"], "canonical 布局首元素越过簇计数")
    gaps = [reader.read_unsigned() for _ in range(cluster["count"] - first)]
    require(table_length >= len(gaps), "canonical 哈希表长度小于元素数")
    cluster["canonicalLayout"] = {"tableLength": table_length, "first": first}


def read_alloc(reader, num_base_objects, num_objects, num_clusters):
    clusters = []
    next_ref = num_base_objects + 1
    for index in range(num_clusters):
        tags = reader.read_fixed() & 0xFFFFFFFF
        cid = tags >> 12
        name = cluster_name(cid)
        cluster = {"index": index, "cid": cid, "name": name,
                   "canonical": bool(tags & 2), "immutable": bool(tags & 4),
                   "start": next_ref}
        count = reader.read_unsigned()
        require(count <= num_objects, "簇对象计数越界：%s" % name)
        if name == "Class":
            cluster["predefinedCount"] = count
            cluster["predefinedCids"] = [reader.read_fixed() for _ in range(count)]
            normal = reader.read_unsigned()
            require(count + normal <= num_objects, "Class 簇对象计数越界")
            cluster["normalCount"] = normal
            count += normal
        elif name == "Code":
            cluster["normalCount"] = count
            cluster["states"] = [reader.read_fixed() for _ in range(count)]
            deferred = reader.read_unsigned()
            cluster["deferredCount"] = deferred
            cluster["states"].extend(reader.read_fixed() for _ in range(deferred))
            count += deferred
        elif name == "Mint":
            cluster["values"] = [reader.read_fixed() for _ in range(count)]
        elif name == "String":
            cluster["encoded"] = [reader.read_unsigned() for _ in range(count)]
        elif name in VARIABLE_LENGTH_ALLOC:
            cluster["lengths"] = [reader.read_unsigned() for _ in range(count)]
        elif name == "Instance":
            cluster["nextFieldWords"] = reader.read_fixed()
            cluster["instanceSizeWords"] = reader.read_fixed()
            require(2 <= cluster["nextFieldWords"] <= cluster["instanceSizeWords"],
                    "Instance 字段/实例尺寸非法")
        else:
            require(name in FIXED_ALLOC, "簇缺少显式 ReadAlloc 分支：%s" % name)
        cluster["count"] = count
        if name in CANONICAL_LAYOUT:
            read_canonical_layout(reader, cluster)
        next_ref += count
        require(next_ref <= num_objects + 1, "分配引用号超过快照对象总数")
        cluster["stop"] = next_ref
        clusters.append(cluster)
    require(next_ref == num_objects + 1,
            "ReadAlloc 末引用号不符：%d != %d" % (next_ref - 1, num_objects))
    return clusters


def read_refs(reader, count):
    for _ in range(count):
        reader.read_ref()


def read_string_cluster(reader, cluster, strings):
    matched = 0
    for offset, alloc_encoded in enumerate(cluster["encoded"]):
        encoded = reader.read_unsigned()
        require(encoded == alloc_encoded,
                "String alloc-fill 编码长度不一致：簇 %d 对象 %d" % (cluster["index"], offset))
        length = encoded >> 1
        two_byte = bool(encoded & 1)
        raw = reader.take(length * (2 if two_byte else 1), "String 填充数据")
        try:
            # Dart TwoByteString 保存 UTF-16 code unit，孤立代理项也是合法原始值；
            # surrogatepass 保留它而不替换，仍属于无损的严格解码。
            errors = "surrogatepass" if two_byte else "strict"
            value = raw.decode("utf-16-le" if two_byte else "latin-1", errors=errors)
        except UnicodeDecodeError as error:
            if cluster["canonical"]:
                raise AuditError("canonical String 严格解码失败：%s" % error) from error
            value = raw.decode("utf-16-le" if two_byte else "latin-1", errors="replace")
        strings[cluster["start"] + offset] = value
        matched += 1
    return matched


def read_instance_cluster(reader, cluster):
    bitmap = reader.read_unsigned()
    next_words = cluster["nextFieldWords"]
    for _ in range(cluster["count"]):
        for word in range(2, next_words):
            if word < 64 and bitmap & (1 << word):
                reader.read_fixed()
                reader.read_fixed()
            else:
                reader.read_ref()


def read_class_cluster(reader, cluster):
    for expected_cid in cluster["predefinedCids"]:
        read_refs(reader, 13)
        cid = reader.read_fixed()
        require(cid == expected_cid, "Class predefined cid 在 alloc-fill 间漂移")
        for _ in range(6):
            reader.read_fixed()
        reader.read_unsigned()
    for _ in range(cluster["normalCount"]):
        read_refs(reader, 13)
        cid = reader.read_fixed()
        require(cid >= 175, "普通 Class 的 cid 落入预定义范围")
        for _ in range(6):
            reader.read_fixed()
        if cid < (1 << 20):
            reader.read_unsigned()


def read_code_cluster(reader, cluster, instructions_table_len):
    instruction_count = 0
    for deferred, count in ((False, cluster["normalCount"]),
                            (True, cluster["deferredCount"])):
        for _ in range(count):
            if not deferred:
                reader.read_unsigned()
                instruction_count += 1
            read_refs(reader, 6)
    require(instruction_count == instructions_table_len,
            "Code 指令表条目数与 Deserialize 头不一致")


def read_object_pool(reader, cluster, contract_pool):
    require(cluster["count"] == 1, "ObjectPool 簇必须只有一个对象")
    alloc_length = cluster["lengths"][0]
    length = reader.read_unsigned()
    require(length == alloc_length, "ObjectPool alloc-fill length 不一致")
    require(length == EXPECTED_POOL_LENGTH, "ObjectPool length 不符")
    require(length == contract_pool["length"], "ObjectPool length 与合同不符")
    slots_by_index = {item[2]: item for item in SLOT_SPECS}
    slot_refs = {}
    counts = Counter()
    for index in range(length):
        entry_bits = reader.byte("ObjectPool entry_bits")
        entry_type = entry_bits & 15
        behavior = entry_bits >> 5
        require(entry_type in (0, 1, 2),
                "ObjectPool entry_bits TypeBits 越界：索引 %d 值 %d" % (index, entry_type))
        require(behavior in (0, 2, 3, 4),
                "ObjectPool entry_bits SnapshotBehavior 非法：索引 %d 值 %d" % (index, behavior))
        ref_id = None
        if behavior == 0:
            if entry_type == 0:
                reader.read_fixed()
                counts["imm"] += 1
            elif entry_type == 1:
                ref_id = reader.read_ref()
                counts["obj"] += 1
            else:
                counts["native"] += 1
        else:
            counts["skip"] += 1
        if index in slots_by_index:
            name = slots_by_index[index][0]
            require(behavior == 0 and entry_type == 1,
                    "对象池四槽 entry 类型非 kTaggedObject：%s" % name)
            slot_refs[name] = ref_id
    actual = {key: counts[key] for key in ("obj", "imm", "skip", "native")}
    require(actual == EXPECTED_ENTRY_COUNTS, "ObjectPool 条目分类计数不符")
    require(actual == contract_pool["entryCounts"], "ObjectPool 条目分类计数与合同不符")
    require(len(slot_refs) == len(SLOT_SPECS), "对象池四槽没有全部读取")
    return length, actual, slot_refs


def read_fill(reader, clusters, instructions_table_len, contract_pool):
    strings = {}
    canonical_alloc = canonical_fill = 0
    pool = None
    for cluster in clusters:
        name = cluster["name"]
        if name == "String":
            matched = read_string_cluster(reader, cluster, strings)
            if cluster["canonical"]:
                canonical_alloc += cluster["count"]
                canonical_fill += matched
        elif name == "Mint":
            pass
        elif name == "Double":
            for _ in range(cluster["count"]):
                reader.read_fixed()
        elif name == "Instance":
            read_instance_cluster(reader, cluster)
        elif name == "TypeArguments":
            for expected in cluster["lengths"]:
                length = reader.read_unsigned()
                require(length == expected, "TypeArguments alloc-fill length 不一致")
                reader.read_fixed()
                reader.read_unsigned()
                read_refs(reader, length + 1)
        elif name in ("TypeParameter", "Type", "FunctionType", "RecordType"):
            for _ in range(cluster["count"]):
                read_refs(reader, READ_FROM_TO[name])
                if name == "Type":
                    reader.read_unsigned()
                elif name == "FunctionType":
                    reader.byte("FunctionType flags")
                    reader.read_fixed()
                    reader.read_fixed()
                elif name == "RecordType":
                    reader.byte("RecordType flags")
                else:
                    reader.read_fixed()
                    reader.read_fixed()
                    reader.byte("TypeParameter flags")
        elif name == "Code":
            read_code_cluster(reader, cluster, instructions_table_len)
        elif name == "Function":
            for _ in range(cluster["count"]):
                read_refs(reader, 4)
                reader.read_unsigned()
                reader.read_fixed()
        elif name == "Closure":
            for _ in range(cluster["count"]):
                read_refs(reader, 6)
        elif name in ("ConstMap", "ConstSet"):
            for _ in range(cluster["count"]):
                read_refs(reader, 5)
        elif name in ("Array", "ImmutableArray"):
            for expected in cluster["lengths"]:
                length = reader.read_unsigned()
                require(length == expected, "%s alloc-fill length 不一致" % name)
                read_refs(reader, length + 1)
        elif name == "WeakArray":
            for expected in cluster["lengths"]:
                length = reader.read_unsigned()
                require(length == expected, "WeakArray alloc-fill length 不一致")
                read_refs(reader, length)
        elif name == "GrowableObjectArray":
            for _ in range(cluster["count"]):
                read_refs(reader, 3)
        elif name == "Record":
            for expected_fields in cluster["lengths"]:
                shape = reader.read_unsigned()
                fields = shape & 0xFFFF
                require(fields == expected_fields, "Record alloc-fill 字段数不一致")
                read_refs(reader, fields)
        elif name == "Class":
            read_class_cluster(reader, cluster)
        elif name == "TypeParameters":
            for _ in range(cluster["count"]):
                read_refs(reader, 4)
        elif name == "PatchClass":
            for _ in range(cluster["count"]):
                read_refs(reader, 2)
        elif name == "ClosureData":
            for _ in range(cluster["count"]):
                read_refs(reader, 2)
                reader.read_unsigned()
        elif name == "FfiTrampolineData":
            for _ in range(cluster["count"]):
                read_refs(reader, 4)
                reader.read_fixed()
                reader.byte("FfiTrampolineData kind")
        elif name == "Field":
            for _ in range(cluster["count"]):
                read_refs(reader, 4)
                reader.read_fixed()
                reader.read_ref()
        elif name == "Script":
            for _ in range(cluster["count"]):
                reader.read_ref()
                reader.read_fixed()
        elif name == "Library":
            for _ in range(cluster["count"]):
                read_refs(reader, 10)
                reader.read_fixed()
                reader.read_fixed()
                reader.byte("Library load_state")
                reader.byte("Library flags")
        elif name == "ObjectPool":
            require(pool is None, "ObjectPool 名称不唯一")
            pool = read_object_pool(reader, cluster, contract_pool)
        elif name == "PcDescriptors":
            for expected in cluster["lengths"]:
                length = reader.read_unsigned()
                require(length == expected, "PcDescriptors alloc-fill length 不一致")
                reader.take(length, "PcDescriptors 数据")
        elif name == "ExceptionHandlers":
            for expected in cluster["lengths"]:
                packed = reader.read_unsigned()
                length = packed >> 1
                require(length == expected, "ExceptionHandlers alloc-fill length 不一致")
                reader.read_ref()
                for _ in range(length):
                    reader.read_fixed()
                    reader.read_fixed()
                    reader.byte("ExceptionHandlers needs_stacktrace")
                    reader.byte("ExceptionHandlers has_catch_all")
                    reader.byte("ExceptionHandlers is_generated")
        elif name == "UnlinkedCall":
            for _ in range(cluster["count"]):
                read_refs(reader, 2)
                reader.byte("UnlinkedCall can_patch")
        elif name == "SubtypeTestCache":
            for _ in range(cluster["count"]):
                reader.read_ref()
                reader.read_fixed()
                reader.read_fixed()
        elif name == "LoadingUnit":
            for _ in range(cluster["count"]):
                reader.read_ref()
                reader.read_fixed()
        elif cluster["cid"] in TYPED_DATA_SIZE:
            size = TYPED_DATA_SIZE[cluster["cid"]]
            for expected in cluster["lengths"]:
                length = reader.read_unsigned()
                require(length == expected, "%s alloc-fill length 不一致" % name)
                reader.take(length * size, "%s 数据" % name)
        else:
            raise AuditError("簇缺少显式 ReadFill 分支：%s" % name)
    require(pool is not None, "ObjectPool 缺失")
    require(canonical_alloc == EXPECTED_CANONICAL_STRINGS
            and canonical_fill == EXPECTED_CANONICAL_STRINGS,
            "canonical String alloc-fill 一致计数不符")
    return strings, pool, canonical_alloc, canonical_fill


def contract_slot_map(contract):
    slots = contract["objectPool"]["slots"]
    require(isinstance(slots, list) and len(slots) == 4, "合同对象池四槽数量不符")
    require(len({item["name"] for item in slots}) == 4, "合同对象池四槽名称不唯一")
    return {item["name"]: item for item in slots}


def verify_contract_constants(contract):
    require(contract["schemaVersion"] == 1, "合同版本不受支持")
    require(contract["policy"] == POLICY, "合同策略不符")
    require(contract["installPermitted"] is False, "合同不得声明安装许可")
    require(contract["notClosedGates"] == [1, 3, 5], "合同未关闭门槛不符")
    snapshot = contract["snapshot"]
    require(number(snapshot["magic"]) == MAGIC and snapshot["kind"] == KIND_FULL_AOT,
            "合同快照 magic/kind 不符")
    require(snapshot["length"] == EXPECTED_SNAPSHOT_LENGTH, "合同快照 length 不符")
    require(snapshot["version"] == HOST_VERSION and snapshot["features"] == FEATURES,
            "合同版本串或特征串不符")
    header = tuple(snapshot[key] for key in ("numBaseObjects", "numObjects", "numClusters",
                                             "instructionsTableLen", "instructionTableDataOffset"))
    require(header == EXPECTED_HEADER, "合同 Deserialize 头常量不符")
    require(contract["objectPool"]["length"] == EXPECTED_POOL_LENGTH,
            "合同 ObjectPool length 不符")
    require(contract["objectPool"]["entryCounts"] == EXPECTED_ENTRY_COUNTS,
            "合同 ObjectPool 条目分类计数不符")
    canonical = contract["canonicalStrings"]
    require(canonical == {"allocCount": EXPECTED_CANONICAL_STRINGS,
                          "fillCount": EXPECTED_CANONICAL_STRINGS,
                          "consistentCount": "117870/117870"},
            "合同 canonical String 计数不符")
    require(contract["verificationBoundary"] == BOUNDARY, "合同静态验证边界不符")
    slot_map = contract_slot_map(contract)
    for name, offset, index, value in SLOT_SPECS:
        item = slot_map[name]
        require(number(item["offset"]) == offset, "合同槽偏移漂移：%s" % name)
        require(item["index"] == index, "合同槽索引漂移：%s" % name)
        require((offset - 16) % 8 == 0 and (offset - 16) // 8 == index,
                "槽偏移与 ObjectPool 索引换算不一致：%s" % name)
        require(item["value"] == value, "合同槽字符串不符：%s" % name)


def verify_snapshot_pool(image, contract):
    """执行版本门禁、ReadAlloc/ReadFill 两趟与对象池四槽交叉核对。"""
    isolate = unique_symbol(image, "_kDartIsolateSnapshotData")
    snapshot_offset = image.file_offset(isolate["value"], isolate["size"])
    raw = image.read(snapshot_offset, isolate["size"])
    try:
        version = raw[20:52].decode("ascii", errors="strict")
    except UnicodeDecodeError as error:
        raise AuditError("宿主版本串不是严格 ASCII：%s" % error) from error
    after_version = 52

    # 第一项语义断言必须是版本门禁；不一致时不能进入官方规则假设的顺序解码。
    require(version == HOST_VERSION, "版本串改动或宿主版本门禁不通过")

    verify_contract_constants(contract)
    vm = unique_symbol(image, "_kDartVmSnapshotData")
    found_symbols = {item["name"]: item for item in (vm, isolate)}
    for name, (expected_value, expected_size) in EXPECTED_SYMBOLS.items():
        symbol = found_symbols[name]
        declared = contract["symbols"]["vm" if name.startswith("_kDartVm") else "isolate"]
        require(declared["name"] == name, "合同快照符号名不符")
        require(number(declared["stValue"]) == expected_value
                and declared["stSize"] == expected_size, "合同快照符号布局不符：%s" % name)
        require(symbol["value"] == expected_value and symbol["size"] == expected_size,
                "动态符号布局不符：%s" % name)

    image_contract = contract["image"]
    require(len(image.data) == image_contract["size"], "宿主 ELF 大小不符")
    require(digest(image.data) == image_contract["sha256"], "宿主 ELF SHA-256 不符")
    require(image.build_id() == image_contract["buildId"], "宿主 ELF Build ID 不符")

    require(len(raw) >= 20, "快照头被截断")
    magic, length, kind = struct.unpack_from("<Iqq", raw, 0)
    require(magic == MAGIC, "快照 magic 不符")
    require(kind == KIND_FULL_AOT, "快照 kind 不是 FullAOT")
    require(length == contract["snapshot"]["length"], "快照 length 与合同不符")
    snapshot_end = length + 4
    require(20 <= snapshot_end <= len(raw), "快照 length 越过动态符号边界")
    features, stream_start = c_string(raw, after_version, snapshot_end, "快照特征串")
    require(features == FEATURES, "特征串改动或与合同不符")

    reader = Reader(raw, stream_start, snapshot_end)
    values = tuple(reader.read_unsigned() for _ in range(5))
    require(values == EXPECTED_HEADER, "Deserialize 头计数不符")
    snapshot = contract["snapshot"]
    declared = tuple(snapshot[key] for key in ("numBaseObjects", "numObjects", "numClusters",
                                                "instructionsTableLen", "instructionTableDataOffset"))
    require(values == declared, "Deserialize 头与合同不符")
    num_base, num_objects, num_clusters, instructions, data_offset = values
    reader.num_objects = num_objects
    clusters = read_alloc(reader, num_base, num_objects, num_clusters)
    alloc_end = reader.pos
    strings, pool, canonical_alloc, canonical_fill = read_fill(
        reader, clusters, instructions, contract["objectPool"])
    length_value, entry_counts, slot_refs = pool

    slot_contract = contract_slot_map(contract)
    slots = []
    for name, offset, index, expected in SLOT_SPECS:
        ref_id = slot_refs[name]
        require(ref_id in strings, "对象池四槽引用未指向 String：%s" % name)
        value = strings[ref_id]
        require(value == expected, "对象池四槽字符串不符：%s" % name)
        require(slot_contract[name]["refId"] == ref_id, "对象池四槽 refId 与合同不符：%s" % name)
        slots.append({"name": name, "immutableOffset": hex(offset), "index": index,
                      "refId": ref_id, "string": value})

    by_name = Counter(cluster["name"] for cluster in clusters)
    object_counts = Counter()
    for cluster in clusters:
        object_counts[cluster["name"]] += cluster["count"]
    require(by_name["ObjectPool"] == 1, "ObjectPool 名称不唯一")
    return {
        "策略": POLICY,
        "版本门禁": {
            "宿主版本串": HOST_VERSION,
            "官方 tag 3.10.1 摘要": OFFICIAL_VERSION,
            "结论": ("宿主版本串 %s != 官方 tag 3.10.1 摘要 %s → "
                     "官方解码规则仅作待验假设；本次结论是宿主自洽证据")
                    % (HOST_VERSION, OFFICIAL_VERSION),
        },
        "快照头": {
            "magic": hex(magic), "length": length, "kind": kind,
            "版本串": version, "特征串": features,
            "num_base_objects": num_base, "num_objects": num_objects,
            "num_clusters": num_clusters, "instructions_table_len": instructions,
            "instruction_table_data_offset": data_offset,
        },
        "簇统计": {
            "簇数": len(clusters), "各类簇数": dict(sorted(by_name.items())),
            "各类对象数": dict(sorted(object_counts.items())), "末引用号": num_objects,
            "ReadAlloc 结束偏移": hex(alloc_end), "ReadFill 结束偏移": hex(reader.pos),
        },
        "对象池": {"length": length_value, "条目分类计数": entry_counts, "四槽": slots},
        "自洽证据": {
            "canonical 字符串 alloc-fill 一致计数": "%d/%d" % (canonical_alloc, canonical_fill),
            "填充引用范围": [1, num_objects],
            "实际目击引用最小值": reader.min_ref, "实际目击引用最大值": reader.max_ref,
            "簇顺序与计数": "ReadFill 严格复用 ReadAlloc 的 1465 个簇及对象计数",
        },
        "安装许可": False,
        "未关闭门槛": [1, 3, 5],
        "限制": BOUNDARY,
    }


def main(argv=None):
    # Windows 控制台代码页可能不是 UTF-8；报告合同要求中文键，固定输出编码避免乱码。
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host-elf", type=Path, required=True, help="本地宿主 libapp.so")
    parser.add_argument("--contract", type=Path, default=DEFAULT_CONTRACT,
                        help="分享 APK 快照对象池合同 JSON")
    args = parser.parse_args(argv)
    try:
        contract = json.loads(args.contract.read_text(encoding="utf-8"))
        image = ElfImage(args.host_elf.read_bytes())
        print(json.dumps(verify_snapshot_pool(image, contract), ensure_ascii=False, indent=2))
    except (AuditError, OSError, UnicodeError, ValueError, TypeError, KeyError,
            json.JSONDecodeError, struct.error) as error:
        # 文件类错误只报类型名，避免把本地路径等环境信息带进可分享的报告。
        reason = type(error).__name__ if isinstance(error, OSError) else str(error)
        print("分享 APK 快照对象池审计失败：%s" % reason)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
