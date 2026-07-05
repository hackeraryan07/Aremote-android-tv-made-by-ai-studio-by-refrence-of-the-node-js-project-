import urllib.request
import re

url = "https://raw.githubusercontent.com/Aymkdn/assistant-freebox-cloud/master/androidtv/androidtvremote2.py"
try:
    with urllib.request.urlopen(url) as response:
        html = response.read().decode()
        for line in html.split('\n'):
            if "hash" in line.lower() or "update" in line.lower():
                print(line)
except Exception as e:
    pass
