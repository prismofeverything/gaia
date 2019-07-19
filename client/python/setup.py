import os
import glob
import setuptools
from distutils.core import setup

with open("README.md", 'r') as readme:
	long_description = readme.read()

setup(
	name='gaia',
	version='0.0.5',
	packages=['gaia'],
	author='Ryan Spangler',
	author_email='ryan.spangler@gmail.com',
	url='https://github.com/prismofeverything/gaia',
	license='MIT',
	scripts=['script/launch-sisyphus.sh'],
	install_requires=['requests', 'pyyaml'],
	long_description=long_description,
	long_description_content_type='text/markdown')
