"""在没有 JDK / Android SDK 的环境里做"找不到符号"级别的静态自查。

覆盖部分常见的 Java 编译错误：

1. **未限定的方法调用**（`foo(...)`）在本类 / 父类 / 接口 / 外层类 / 静态导入里不存在；
2. **未限定的变量**（字段、枚举常量）不存在；
3. **静态访问写错**（`ExerciseType.WALL_SITX`、`Session.getResultTextX()`）——
   只要前缀是本仓库的类，就检查成员是否存在；
4. **方法/本仓库构造器参数个数对不上**（重载全都匹配不上、也不是可变参数）；
5. `import com.zxw...` 指向本仓库里不存在的类型。

保守策略：如果某类的父类/接口或静态导入来自本仓库之外（Android SDK、JUnit 等），
就无法判断它继承了哪些成员，直接跳过该类，避免误报。

依赖：Python 3 + javalang（pip install javalang）
用法：python3 tools/java_static_check.py
"""
import glob
import sys

import javalang
from javalang import tree as jt

ROOT = 'app/src'

# Object / Enum 自带的方法，未限定调用时不需要在本类里声明
OBJECT_METHODS = {
    'toString', 'equals', 'hashCode', 'getClass', 'notify', 'notifyAll',
    'wait', 'clone', 'finalize',
}
ENUM_METHODS = {'values', 'valueOf', 'name', 'ordinal', 'compareTo'}


class ClassInfo:
    def __init__(self, name, path, enclosing=None):
        self.name = name
        self.path = path
        self.enclosing = enclosing
        self.methods = {}        # name -> [参数个数, ...]
        self.varargs = set()     # 可变参数的方法名
        self.fields = set()
        self.field_types = {}
        self.is_enum = False
        self.supers = []         # 父类 + 接口的简单名

    def qualified_name(self):
        names = []
        node = self
        while node is not None:
            names.append(node.name)
            node = node.enclosing
        return '.'.join(reversed(names))


def iter_nodes(root, kind, skip_nested=True):
    """在方法体里递归找某类节点；skip_nested=True 时不进入匿名类/局部类。"""
    stack = [root]
    while stack:
        node = stack.pop()
        if isinstance(node, list):
            stack.extend(node)
            continue
        if not isinstance(node, jt.Node):
            continue
        if isinstance(node, kind):
            yield node
        if skip_nested and (
                (isinstance(node, jt.ClassCreator) and node.body)
                or isinstance(node, (jt.ClassDeclaration, jt.EnumDeclaration,
                                     jt.InterfaceDeclaration, jt.AnnotationDeclaration))):
            continue
        stack.extend(node.children)


def selector_ids(root):
    """链式调用 a().b() 里作为 selectors 出现的节点 id。"""
    ids = set()
    for node in iter_nodes(root, jt.Node):
        for s in (getattr(node, 'selectors', None) or []):
            if isinstance(s, jt.Node):
                ids.add(id(s))
    return ids


def collect_locals(method):
    """方法/构造器里声明过的名字（参数、局部变量、catch、for-each、lambda 参数）。"""
    names = {p.name for p in (method.parameters or [])}
    body = getattr(method, 'body', None)
    if body is None:
        return names
    for node in iter_nodes(body, jt.VariableDeclaration):
        for d in node.declarators:
            names.add(d.name)
    for node in iter_nodes(body, jt.CatchClauseParameter):
        names.add(node.name)
    for node in iter_nodes(body, jt.TryResource):
        if node.name:
            names.add(node.name)
    for node in iter_nodes(body, jt.LambdaExpression):
        for p in (node.parameters or []):
            names.add(getattr(p, 'name', p))
    return names


def collect_var_types(method, chain):
    """局部变量 / 参数 / 字段的 简单名 -> 类型简单名，用于判断 `session.getXxx()` 这种调用。"""
    types = {}
    for c in chain:
        if c is None:
            continue
        for name in getattr(c, 'field_types', {}) or {}:
            types.setdefault(name, c.field_types[name])
    for p in (method.parameters or []):
        types[p.name] = simple_name(getattr(p.type, 'name', ''))
    body = getattr(method, 'body', None)
    if body is None:
        return types
    for node in iter_nodes(body, jt.VariableDeclaration):
        if getattr(node.type, 'dimensions', None):
            continue
        tname = simple_name(getattr(node.type, 'name', ''))
        if not tname:
            continue
        for d in node.declarators:
            types[d.name] = tname
    for node in iter_nodes(body, jt.CatchClauseParameter):
        types.setdefault(node.name, simple_name(getattr(node.types[0], 'name', '')))
    return types


def collect_class_body(cls):
    methods, varargs, fields = {}, set(), set()
    for m in cls.methods:
        count = len(m.parameters or [])
        if (m.parameters or []) and m.parameters[-1].varargs:
            varargs.add(m.name)
        methods.setdefault(m.name, []).append(count)
    for c in cls.constructors:
        methods.setdefault(c.name, []).append(len(c.parameters or []))
        if (c.parameters or []) and c.parameters[-1].varargs:
            varargs.add(c.name)
    field_types = {}
    for f in cls.fields:
        tname = '' if getattr(f.type, 'dimensions', None) else simple_name(getattr(f.type, 'name', ''))
        for d in f.declarators:
            fields.add(d.name)
            if tname:
                field_types[d.name] = tname
    if isinstance(cls, jt.EnumDeclaration):
        for c in cls.body.constants:
            fields.add(c.name)
    return methods, varargs, fields, field_types


def walk(tree_root, path=''):
    """遍历整棵树，产出所有类型定义。"""
    results = []

    def visit(node, enclosing):
        if isinstance(node, (jt.ClassDeclaration, jt.EnumDeclaration,
                             jt.InterfaceDeclaration, jt.AnnotationDeclaration)):
            info = ClassInfo(node.name, path, enclosing)
            info.is_enum = isinstance(node, jt.EnumDeclaration)
            (info.methods, info.varargs,
             info.fields, info.field_types) = collect_class_body(node)
            extends = getattr(node, 'extends', None)
            if extends is not None:
                for e in (extends if isinstance(extends, list) else [extends]):
                    info.supers.append(simple_name(e.name))
            for e in (getattr(node, 'implements', None) or []):
                info.supers.append(simple_name(e.name))
            results.append(info)
            for child in node.children:
                visit(child, info)
            return
        if isinstance(node, list):
            for c in node:
                visit(c, enclosing)
            return
        if not isinstance(node, jt.Node):
            return
        for child in node.children:
            visit(child, enclosing)

    visit(tree_root, None)
    return results


def simple_name(type_name):
    return type_name.split('<')[0].split('.')[-1]


def resolve_methods(info, by_name, seen=None):
    """本类 + 父类/接口 + 外层类链上的方法：name -> [参数个数, ...]。"""
    seen = seen or set()
    if id(info) in seen:
        return {}
    seen.add(id(info))
    merged = {name: list(counts) for name, counts in info.methods.items()}
    for s in info.supers:
        target = by_name.get(s)
        if target is not None:
            for name, counts in resolve_methods(target, by_name, seen).items():
                merged.setdefault(name, []).extend(counts)
    if info.enclosing is not None:
        for name, counts in resolve_methods(info.enclosing, by_name, seen).items():
            merged.setdefault(name, []).extend(counts)
    return merged


def resolve_fields(info, by_name, seen=None):
    """本类 + 父类/接口 + 外层类链上的字段/枚举常量名。"""
    seen = seen or set()
    if id(info) in seen:
        return set()
    seen.add(id(info))
    names = set(info.fields)
    for s in info.supers:
        target = by_name.get(s)
        if target is not None:
            names |= resolve_fields(target, by_name, seen)
    if info.enclosing is not None:
        names |= resolve_fields(info.enclosing, by_name, seen)
    return names


def resolve_varargs(info, by_name, seen=None):
    seen = seen or set()
    if id(info) in seen:
        return set()
    seen.add(id(info))
    names = set(info.varargs)
    for s in info.supers:
        target = by_name.get(s)
        if target is not None:
            names |= resolve_varargs(target, by_name, seen)
    if info.enclosing is not None:
        names |= resolve_varargs(info.enclosing, by_name, seen)
    return names


def has_external_supers(info, by_name):
    return any(s and s not in by_name for s in info.supers)


def arity_ok(counts, varargs, argc):
    if varargs:
        return True
    return any(argc == c for c in counts)


def check_constructors(path, tree, by_qualified, by_simple):
    """只检查能唯一定位到本仓库类型的 new 调用；不推断参数类型或可见性。"""
    problems, checked = [], 0
    for call in iter_nodes(tree, jt.ClassCreator, skip_nested=False):
        ref, names = call.type, []
        while ref is not None:
            names.append(ref.name)
            ref = getattr(ref, 'sub_type', None)
        owner = by_qualified.get('.'.join(names))
        if owner is None and len(by_simple.get(names[-1], [])) == 1:
            owner = by_simple[names[-1]][0]
        if owner is None:
            continue
        checked += 1
        argc = len(call.arguments or [])
        # 无显式构造器时 Java 提供无参默认构造器。
        counts = owner.methods.get(owner.name, [0])
        if not arity_ok(counts, owner.name in owner.varargs, argc):
            problems.append((path, call.position, owner.name,
                             f'new {owner.qualified_name()}(...) 参数个数 {argc} 不匹配'))
    return problems, checked


def main():
    files = sorted(glob.glob(ROOT + '/**/*.java', recursive=True))
    trees, all_classes = {}, []
    for path in files:
        tree = javalang.parse.parse(open(path, encoding='utf-8').read())
        trees[path] = tree
        all_classes.extend(walk(tree, path))

    by_name = {}
    for c in all_classes:
        if c.enclosing is None:
            by_name[c.name] = c
    by_qualified = {c.qualified_name(): c for c in all_classes}
    by_simple = {}
    for c in all_classes:
        by_simple.setdefault(c.name, []).append(c)
    type_names = {c.name for c in all_classes}

    repo_static, external_static = {}, set()
    problems = []
    checked_constructors = 0
    for path, tree in trees.items():
        bad, count = check_constructors(path, tree, by_qualified, by_simple)
        problems.extend(bad)
        checked_constructors += count

    for path, tree in trees.items():
        names = set()
        for imp in tree.imports:
            if not imp.static:
                continue
            if imp.path.startswith('com.zxw'):
                names.add(imp.path.rsplit('.', 1)[-1])
            else:
                external_static.add(path)
        repo_static[path] = names

        for imp in tree.imports:
            if imp.static or not imp.path.startswith('com.zxw'):
                continue
            last = imp.path.rsplit('.', 1)[-1]
            if last in ('R', 'BuildConfig'):      # 由构建生成，源码里没有
                continue
            if last in type_names or imp.path in by_qualified:
                continue
            problems.append((path, imp.position, '(import)', imp.path))

    for path, tree in trees.items():
        targets = []

        def gather(node, chain):
            if isinstance(node, (jt.ClassDeclaration, jt.EnumDeclaration,
                                 jt.InterfaceDeclaration)):
                info = next((c for c in all_classes
                             if c.name == node.name and c.path == path), None)
                chain = chain + ([info] if info else [])
            elif isinstance(node, (jt.MethodDeclaration, jt.ConstructorDeclaration)):
                targets.append((node, list(chain)))
            elif isinstance(node, jt.ClassCreator) and node.body:
                anon = ClassInfo('<anonymous>', path)
                anon.supers.append('__external__')
                chain = chain + [anon]
            if isinstance(node, list):
                for c in node:
                    gather(c, chain)
                return
            if not isinstance(node, jt.Node):
                return
            for child in node.children:
                gather(child, chain)

        gather(tree, [])

        for method, chain in targets:
            if not chain or any(c is None for c in chain):
                continue
            info = chain[-1]
            # 继承了外部类（Activity/View…）的类，无法判断未限定调用是否来自父类，
            # 但仍然可以检查 `Session.foo()`、`session.foo()` 这类明确前缀的访问。
            check_unqualified = not (has_external_supers(info, by_name)
                                     or path in external_static)
            body = getattr(method, 'body', None)
            if body is None:
                continue

            methods_ok, varargs_ok, fields_ok = {}, set(), set()
            for c in chain:
                for name, counts in resolve_methods(c, by_name).items():
                    methods_ok.setdefault(name, []).extend(counts)
                fields_ok |= resolve_fields(c, by_name)
                varargs_ok |= resolve_varargs(c, by_name)
            for name in OBJECT_METHODS | ENUM_METHODS | repo_static[path]:
                methods_ok.setdefault(name, list(range(0, 6)))
            fields_ok |= repo_static[path]

            locals_ = collect_locals(method)
            var_types = collect_var_types(method, chain)
            selectors = selector_ids(body)

            for call in iter_nodes(body, jt.MethodInvocation):
                if id(call) in selectors:
                    continue
                argc = len(call.arguments or [])
                if call.qualifier in (None, ''):
                    if not check_unqualified:
                        continue
                    if call.member in ('super', 'this'):
                        continue
                    if call.member not in methods_ok:
                        problems.append((path, call.position, info.name,
                                         call.member + '()'))
                    elif not arity_ok(methods_ok[call.member], varargs_ok, argc):
                        problems.append((path, call.position, info.name,
                                         f'{call.member}(...) 参数个数 {argc} 不匹配'))
                    continue
                if not isinstance(call.qualifier, str) or '.' in call.qualifier:
                    continue
                owner = by_qualified.get(call.qualifier) or by_name.get(call.qualifier)
                if owner is None and call.qualifier in var_types:
                    tname = var_types[call.qualifier]
                    owner = by_qualified.get(tname) or by_name.get(tname)
                if owner is None:
                    continue                      # 外部类型，无法检查
                if has_external_supers(owner, by_name):
                    continue                      # 继承了外部类的方法，无法判断
                methods = resolve_methods(owner, by_name)
                for name, counts in list(methods.items()):
                    if name in OBJECT_METHODS | ENUM_METHODS:
                        methods[name] = counts + list(range(0, 6))
                if owner.is_enum:
                    methods.setdefault('values', []).append(0)
                    methods.setdefault('valueOf', []).append(1)
                if call.member not in methods:
                    problems.append((path, call.position, info.name,
                                     f'{call.qualifier}.{call.member}()'))
                elif not arity_ok(methods[call.member],
                                  resolve_varargs(owner, by_name), argc):
                    problems.append((path, call.position, info.name,
                                     f'{call.qualifier}.{call.member}(...) 参数个数 {argc} 不匹配'))

            for ref in iter_nodes(body, jt.MemberReference):
                if id(ref) in selectors:
                    continue
                name = ref.member
                if ref.qualifier in (None, ''):
                    if not check_unqualified:
                        continue
                    if name in locals_ or name in fields_ok or name[:1].isupper():
                        continue
                    problems.append((path, ref.position, info.name, name))
                    continue
                if not isinstance(ref.qualifier, str) or '.' in ref.qualifier:
                    continue
                owner = by_qualified.get(ref.qualifier) or by_name.get(ref.qualifier)
                if owner is None and ref.qualifier in var_types:
                    tname = var_types[ref.qualifier]
                    owner = by_qualified.get(tname) or by_name.get(tname)
                if owner is None:
                    continue
                if has_external_supers(owner, by_name):
                    continue
                if name in resolve_fields(owner, by_name):
                    continue
                problems.append((path, ref.position, info.name,
                                 f'{ref.qualifier}.{name}'))

    for path, pos, cls, name in problems:
        line = pos.line if pos else '?'
        print(f'{path}:{line}: {cls}: 找不到符号 {name}')

    print(f'\n检查 {len(files)} 个文件，{len(all_classes)} 个类型，'
          f'{checked_constructors} 个本仓库构造调用，问题 {len(problems)} 处')
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main())
