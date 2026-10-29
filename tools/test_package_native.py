"""Regression checks for dpkg's explicit-directory requirement."""
import gzip
import io
import tarfile
import unittest
from package_native import archive, changelog


class PackageArchiveTest(unittest.TestCase):
    def test_directories_precede_children_and_have_installable_permissions(self):
        entries = [("usr/bin/rsync-airlift", b"ELF", 0o755),
                   ("usr/share/doc/rsync-airlift/README.md", b"docs", 0o644),
                   ("usr/share/doc/rsync-airlift/NATIVE.md", b"native docs", 0o644)]
        with tarfile.open(fileobj=io.BytesIO(archive(entries, 123)), mode="r:gz") as tar:
            seen = set()
            for member in tar.getmembers():
                parent = member.name.rpartition('/')[0]
                if parent:
                    self.assertIn(parent, seen, member.name)
                self.assertNotIn(member.name, seen)
                self.assertEqual((member.uid, member.gid, member.mtime), (0, 0, 123))
                if member.isdir():
                    self.assertEqual(member.mode, 0o755)
                seen.add(member.name)
            for name, content, mode in entries:
                self.assertEqual(tar.getmember(name).mode, mode)
                self.assertEqual(tar.extractfile(name).read(), content)
            self.assertIn('usr/share/doc/rsync-airlift', seen)

    def test_control_archive_remains_flat_and_reproducible(self):
        entries = [("control", b"Package: test\n", 0o644)]
        result = archive(entries, 123)
        self.assertEqual(result, archive(entries, 123))
        with tarfile.open(fileobj=io.BytesIO(result), mode="r:gz") as tar:
            self.assertEqual(tar.getnames(), ['control'])

    def test_changelog_is_debian_formatted_and_reproducible(self):
        result = changelog("0.1.0", "Rsync Airlift contributors", 1700000000)
        self.assertEqual(result, changelog("0.1.0", "Rsync Airlift contributors", 1700000000))
        self.assertEqual(result[4:8], b"\0\0\0\0")  # no gzip timestamp
        lines = gzip.decompress(result).decode().splitlines()
        self.assertEqual(lines[0], "rsync-airlift (0.1.0) stable; urgency=medium")
        self.assertTrue(lines[2].startswith("  * "))
        self.assertEqual(lines[-1], " -- Rsync Airlift contributors  Tue, 14 Nov 2023 22:13:20 +0000")


if __name__ == '__main__':
    unittest.main()
