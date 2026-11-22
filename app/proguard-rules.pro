# Классы, которые библиотеки создают через рефлексию. R8 не видит этих вызовов и вырезает конструкторы:
# без них виджет навсегда остаётся на крутилке загрузки (WorkManager: «OverwritingInputMerger has no zero argument constructor»).

# Виджет Glance рисуется фоновой задачей WorkManager.
-keep class * extends androidx.work.InputMerger { <init>(); }
-keep class * extends androidx.work.ListenableWorker { <init>(android.content.Context, androidx.work.WorkerParameters); }

# Нажатие на товар в виджете запускает наш ActionCallback по имени класса.
-keep class * extends androidx.glance.appwidget.action.ActionCallback { <init>(); }
