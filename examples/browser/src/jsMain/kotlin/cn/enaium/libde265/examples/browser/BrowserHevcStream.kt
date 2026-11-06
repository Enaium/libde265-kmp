/*
 * Copyright (c) 2026 Enaium
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package cn.enaium.libde265.examples.browser

/**
 * A 64x64, two-frame HEVC stream (libx265, yuv420p, in-band parameter sets).
 * Decoded when the browser exposes no HEVC WebCodecs encoder, so the demo still
 * proves the libde265 binding on the web platform.
 */
internal const val BROWSER_HEVC_STREAM_BASE64: String =
        "AAAAAUABDAH//wFgAAADAJAAAAMAAAMAHpKAkAAAAAFCAQEBYAAAAwCQAAADAAADAB6gIIEFlkqSTK8BaAgAAAMACAAAAwAoQAAA" +
        "AAFEAcFytCJAAAABTgEF///////////2LKLeCbUXR9u7VaT+f8L8TngyNjUgKGJ1aWxkIDIxNykgLSA0LjMrMS1lOWI4ODEyOltN" +
        "YWMgT1MgWF1bY2xhbmcgMjEuMC4wXVs2NCBiaXRdIDhiaXQrMTBiaXQrMTJiaXQgLSBILjI2NS9IRVZDIGNvZGVjIC0gQ29weXJp" +
        "Z2h0IDIwMTMtMjAxOCAoYykgTXVsdGljb3Jld2FyZSwgSW5jIC0gaHR0cDovL3gyNjUub3JnIC0gb3B0aW9uczogY3B1aWQ9OTgg" +
        "ZnJhbWUtdGhyZWFkcz0xIG5vLXdwcCBuby1wbW9kZSBuby1wbWUgbm8tcHNuciBuby1zc2ltIGxvZy1sZXZlbD0tMSBiaXRkZXB0" +
        "aD04IGlucHV0LWNzcD0xIGZwcz01LzEgaW5wdXQtcmVzPTY0eDY0IGludGVybGFjZT0wIHRvdGFsLWZyYW1lcz0wIGxldmVsLWlk" +
        "Yz0wIGhpZ2gtdGllcj0xIHVoZC1iZD0wIHJlZj0zIG5vLWFsbG93LW5vbi1jb25mb3JtYW5jZSByZXBlYXQtaGVhZGVycyBhbm5l" +
        "eGIgbm8tYXVkIG5vLWVvYiBuby1lb3Mgbm8taHJkIGluZm8gaGFzaD0wIHRlbXBvcmFsLWxheWVycz0wIG9wZW4tZ29wIG1pbi1r" +
        "ZXlpbnQ9MiBrZXlpbnQ9MiBnb3AtbG9va2FoZWFkPTAgYmZyYW1lcz0wIGItYWRhcHQ9MiBuby1iLXB5cmFtaWQgYmZyYW1lLWJp" +
        "YXM9MCByYy1sb29rYWhlYWQ9MjAgbG9va2FoZWFkLXNsaWNlcz0wIHNjZW5lY3V0PTQwIG5vLWhpc3Qtc2NlbmVjdXQgcmFkbD0w" +
        "IG5vLXNwbGljZSBuby1pbnRyYS1yZWZyZXNoIGN0dT02NCBtaW4tY3Utc2l6ZT04IG5vLXJlY3Qgbm8tYW1wIG1heC10dS1zaXpl" +
        "PTMyIHR1LWludGVyLWRlcHRoPTEgdHUtaW50cmEtZGVwdGg9MSBsaW1pdC10dT0wIHJkb3EtbGV2ZWw9MCBkeW5hbWljLXJkPTAu" +
        "MDAgbm8tc3NpbS1yZCBzaWduaGlkZSBuby10c2tpcCBuci1pbnRyYT0wIG5yLWludGVyPTAgbm8tY29uc3RyYWluZWQtaW50cmEg" +
        "c3Ryb25nLWludHJhLXNtb290aGluZyBtYXgtbWVyZ2U9MyBsaW1pdC1yZWZzPTEgbm8tbGltaXQtbW9kZXMgbWU9MSBzdWJtZT0y" +
        "IG1lcmFuZ2U9NTcgdGVtcG9yYWwtbXZwIG5vLWZyYW1lLWR1cCBuby1obWUgd2VpZ2h0cCBuby13ZWlnaHRiIG5vLWFuYWx5emUt" +
        "c3JjLXBpY3MgZGVibG9jaz0wOjAgc2FvIG5vLXNhby1ub24tZGVibG9jayByZD0zIHNlbGVjdGl2ZS1zYW89NCBlYXJseS1za2lw" +
        "IHJza2lwIG5vLWZhc3QtaW50cmEgbm8tdHNraXAtZmFzdCBuby1jdS1sb3NzbGVzcyBiLWludHJhIG5vLXNwbGl0cmQtc2tpcCBy" +
        "ZHBlbmFsdHk9MCBwc3ktcmQ9Mi4wMCBwc3ktcmRvcT0wLjAwIG5vLXJkLXJlZmluZSBuby1sb3NzbGVzcyBjYnFwb2Zmcz0wIGNy" +
        "cXBvZmZzPTAgcmM9Y3JmIGNyZj0zMi4wIHFjb21wPTAuNjAgcXBzdGVwPTQgc3RhdHMtd3JpdGU9MCBzdGF0cy1yZWFkPTAgaXBy" +
        "YXRpbz0xLjQwIGFxLW1vZGU9MiBhcS1zdHJlbmd0aD0xLjAwIGN1dHJlZSB6b25lLWNvdW50PTAgbm8tc3RyaWN0LWNiciBxZy1z" +
        "aXplPTMyIG5vLXJjLWdyYWluIHFwbWF4PTY5IHFwbWluPTAgbm8tY29uc3QtdmJ2IHNhcj0xIG92ZXJzY2FuPTAgdmlkZW9mb3Jt" +
        "YXQ9NSByYW5nZT0wIGNvbG9ycHJpbT0yIHRyYW5zZmVyPTIgY29sb3JtYXRyaXg9MiBjaHJvbWFsb2M9MCBkaXNwbGF5LXdpbmRv" +
        "dz0wIGNsbD0wLDAgbWluLWx1bWE9MCBtYXgtbHVtYT0yNTUgbG9nMi1tYXgtcG9jLWxzYj04IHZ1aS10aW1pbmctaW5mbyB2dWkt" +
        "aHJkLWluZm8gc2xpY2VzPTEgbm8tb3B0LXFwLXBwcyBuby1vcHQtcmVmLWxpc3QtbGVuZ3RoLXBwcyBuby1tdWx0aS1wYXNzLW9w" +
        "dC1ycHMgc2NlbmVjdXQtYmlhcz0wLjA1IG5vLW9wdC1jdS1kZWx0YS1xcCBuby1hcS1tb3Rpb24gbm8taGRyMTAgbm8taGRyMTAt" +
        "b3B0IG5vLWRoZHIxMC1vcHQgbm8taWRyLXJlY292ZXJ5LXNlaSBhbmFseXNpcy1yZXVzZS1sZXZlbD0wIGFuYWx5c2lzLXNhdmUt" +
        "cmV1c2UtbGV2ZWw9MCBhbmFseXNpcy1sb2FkLXJldXNlLWxldmVsPTAgc2NhbGUtZmFjdG9yPTAgcmVmaW5lLWludHJhPTAgcmVm" +
        "aW5lLWludGVyPTAgcmVmaW5lLW12PTEgcmVmaW5lLWN0dS1kaXN0b3J0aW9uPTAgbm8tbGltaXQtc2FvIGN0dS1pbmZvPTAgbm8t" +
        "bG93cGFzcy1kY3QgcmVmaW5lLWFuYWx5c2lzLXR5cGU9MCBjb3B5LXBpYz0xIG1heC1hdXNpemUtZmFjdG9yPTEuMCBuby1keW5h" +
        "bWljLXJlZmluZSBuby1zaW5nbGUtc2VpIG5vLWhldmMtYXEgbm8tc3Z0IG5vLWZpZWxkIHFwLWFkYXB0YXRpb24tcmFuZ2U9MS4w" +
        "MCBzY2VuZWN1dC1hd2FyZS1xcD0wY29uZm9ybWFuY2Utd2luZG93LW9mZnNldHMgcmlnaHQ9MCBib3R0b209MCBkZWNvZGVyLW1h" +
        "eC1yYXRlPTAgbm8tdmJ2LWxpdmUtbXVsdGktcGFzcyBuby1tY3N0ZiBuby1zYnJjIG5vLWZyYW1lLXJjgAAAASgBrxmAvNfhalHn" +
        "baKB4D8NmGXwt7QmnGMLXQ3nt9M8U9Yzk3Kv4cuqgj43XpCIJBaYfwYNhoXP0+0rx6t///+DfpE9KE/ymOudcEYZh2zItRcBkwdO" +
        "JP4j+4hiX+YPK6Ey2ejEsPIJcBYJB5C/U/e2E32ipsKhNrrB1YgYX4Wsx33//dRscyh25xdWMNlSt/5BaIW6s/DDuyZiUriWbYn1" +
        "UTJQKHAx4jU2jQV4YOCoG2clAeA0EoOek5CKcPRYIYmroLzVjKhRdklcl4CTUEnc5AlNCFHzQKtvN0JpLL1vSuYuRh3FWRyQc3q4" +
        "TXxcKLUExCHNDq0bwaHLW6Yb5uViIVjX4nvm0TY8rAP19KgM7dWgkza2t7Gys9bPv14OGm8utZ0vJeD41fZW2McupIUHPBD48anM" +
        "8Rd7EqO5HuO5kImb12UgncLCpIwtqS6C2Hgk9UvshAiTAYJ8y2gpKDamB03U4/OdFKw7BAil9sUnVXPEh5SJZvTgyrJybsxN5wJC" +
        "6zUC3XyJ/zY1JfsmZjm7gGoXHo39CEXWtVvQHESfFouDA1DLry94d+3aswWZZPMFdNOOjTqJdUCFwXbocrfJXDxDIfjw68ibuoYS" +
        "076FTAWR1ATQH9lRGh/OVBcM6tjh8WqlbwhPWrHW/IoZHsFkOgZLJekqtx4PwdR0oNGS3C6kAE0qG/utnRsfVjNUoAL3AVKTuGeR" +
        "bQZikZTvaAQMKk6hyc6IUn5E9eJkd2Q4eI/9WQrwgRmmm1HcwicQ1vh7htxWf5G38yAkivpBuneKL+zvtxHrVqxfRMiLGROYa1Zd" +
        "uA2YgOy1O3Y8pFVhjahFM2lXoX0FqDNyzAFKjpYBo9uxNOJ3yJYN4S0W1LI0OQ38EW085iXo1O+9x/8GlX+Wuu+O/8HbbKF+ibnI" +
        "11CLaG+4BsbWuFxtMjfsgudQEEYEx4wTtetigC49dChodFmy9mUsj3aJ+kjProKuvQXYP24tdhfjJQBF45DwyQvmhHUmRJXisjIQ" +
        "3rMqxb4dtkEcGMcXyFtWb53BwQgwUcMJmwOcdMB78AQM0XohRYQQmuc9I3YlvXGBz8+iZ9w/Y3VLL3BPZ6EP6lcVgbyoD2xgAAAA" +
        "AQIB0Al+ERjMtrU1kvAoT/vzj4aMnKG9+uf84IvU5dAISham0UsvgrOfg7OwopsopqyzY5Mh6Lr+HEpeXxM1nj4Uv3ZyERkdqJSM" +
        "TzhT7l1NeXlKDSq+4hmtRgrqTh5AA9/BJCMLY2NUoW53B131n61VMdWdupwrs2RVVVFSqpWXyMamo8wmrhhEJx9lgFV+H43LpZHh" +
        "FOZlSkBvfb0woaEh6AnMcqPM0SsEyC3KUsV6ZmI10KAnkr3hNwOd+Cb1ZFGGK6glsdS9lfE4bNhnEB+lVzV82ZByBs2T70A="
