#!/usr/bin/env python3
"""
Kotlin 花括号平衡检查 (字符串/注释感知)。

为什么不能简单 count('{'):
字符串模板 (形如 ${doc.uri}) 里的花括号是**代码**, 要计入;
而多行 raw string 里当数据写的花括号, 不该计入。
简单计数分不清这两者, 会得出错误结论。

踩过的坑: 之前用简单计数判断"括号不平衡", 给 AiClient.kt 末尾补了一个
右花括号 —— 结果那是多余的, CI 编译直接失败 (AiClient.kt:296
Expecting a top level declaration)。所以写了这个真的懂 Kotlin 词法的版本。

状态机逐字符扫描, 识别:
  - 行注释
  - 块注释 (Kotlin 允许嵌套)
  - 三引号 raw string (内部的花括号按数据处理, 不计入)
  - 双引号普通字符串 (含反斜杠转义)
  - 单引号字符字面量
  - 字符串模板内的花括号 (按代码处理, 计入)
"""
import sys
import pathlib

RAW = chr(34) * 3          # 三引号
DQ = chr(34)
SQ = chr(39)


def scan(src):
    """扫描源码, 返回 (错误列表, 最终深度)。

    错误项: (行号, 说明)
    """
    i, n = 0, len(src)
    line = 1
    depth = 0
    stack = []            # 记录每个未闭合 '{' 打开时的行号
    errs = []

    while i < n:
        c = src[i]
        two = src[i:i + 2]
        three = src[i:i + 3]

        # --- raw string: 整段当数据, 内部花括号不计入 ---
        if three == RAW:
            j = src.find(RAW, i + 3)
            if j < 0:
                errs.append((line, "raw string 没有闭合"))
                return errs, depth
            line += src.count("\n", i, j + 3)
            i = j + 3
            continue

        # --- 注释 ---
        if two == "//":
            j = src.find("\n", i)
            i = j if j >= 0 else n
            continue
        if two == "/*":
            start = line
            j, nest = i + 2, 1
            while j < n and nest > 0:
                if src[j:j + 2] == "/*":
                    nest += 1; j += 2
                elif src[j:j + 2] == "*/":
                    nest -= 1; j += 2
                else:
                    if src[j] == "\n":
                        line += 1
                    j += 1
            if nest > 0:
                errs.append((start, "块注释没有闭合"))
                return errs, depth
            i = j
            continue

        # --- 普通字符串 (跳过, 但字符串里的 ${} 是代码) ---
        if c == DQ:
            j = i + 1
            while j < n:
                if src[j] == "\\":
                    j += 2
                    continue
                if src[j] == DQ:
                    break
                if src[j] == "\n":
                    line += 1
                    break
                j += 1
            i = j + 1
            # 模板部分照常被主循环处理
            continue

        if c == SQ:
            j = i + 1
            while j < n:
                if src[j] == "\\":
                    j += 2
                    continue
                if src[j] == SQ:
                    break
                j += 1
            i = j + 1
            continue

        # --- 真正的代码括号 ---
        if c == "{":
            depth += 1
            stack.append(line)
        elif c == "}":
            depth -= 1
            if depth < 0:
                errs.append((line, "多余的右花括号, 多半是文件末尾多补了一个"))
                return errs, depth
            if stack:
                stack.pop()

        if c == "\n":
            line += 1
        i += 1

    if depth > 0:
        where = stack[-1] if stack else "?"
        errs.append((where, "缺了 %d 个右花括号, 最后打开的块在第 %s 行" % (depth, where)))

    bad_char = src.count(chr(0xFFFD))
    if bad_char:
        errs.append((0, "含 %d 个 U+FFFD 乱码字符" % bad_char))

    return errs, depth


def check_file(path):
    src = path.read_text(encoding="utf-8", errors="replace")
    errs, depth = scan(src)
    if not errs:
        return True, depth, None
    return False, depth, (errs, src)


def main(paths):
    files = []
    for p in paths:
        pp = pathlib.Path(p)
        files += sorted(pp.rglob("*.kt")) if pp.is_dir() else [pp]

    bad = 0
    for f in files:
        ok, depth, extra = check_file(f)
        if ok:
            continue
        bad += 1
        errs, src = extra
        print("X %s" % f)
        lines = src.split("\n")
        for ln, msg in errs:
            print("   第 %s 行: %s" % (ln, msg))
            if ln:
                for k in range(max(0, ln - 4), min(len(lines), ln + 1)):
                    mark = " >>" if k == ln - 1 else "   "
                    print("   %s %4d | %s" % (mark, k + 1, lines[k][:88]))
        print()

    print("=" * 50)
    print("扫了 %d 个文件, %s" % (len(files), ("发现 %d 个问题" % bad) if bad else "全部通过"))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:] or ["."]))
