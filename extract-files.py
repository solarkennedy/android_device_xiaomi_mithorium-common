#!/usr/bin/env -S PYTHONPATH=../../../tools/extract-utils python3
#
# SPDX-FileCopyrightText: The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
#

from extract_utils.file import File
from extract_utils.fixups_blob import (
    BlobFixupCtx,
    blob_fixup,
)
from extract_utils.main import (
    ExtractUtils,
    ExtractUtilsModule,
)

namespace_imports = []

module = ExtractUtilsModule(
    'mithorium-common',
    'xiaomi',
    namespace_imports=namespace_imports,
    add_firmware_proprietary_file=True,
    skip_main_proprietary_file=True,
)
module.add_proprietary_file('proprietary-files-misc.txt')
module.add_proprietary_file('proprietary-files-qc-sys.txt')
module.add_proprietary_file('proprietary-files-qc-vndr.txt')

if __name__ == '__main__':
    utils = ExtractUtils.device(module)
    utils.run()
