(ns excel-repl.interop)

(import ExcelDna.Integration.ExcelReference)
(import ExcelDna.Integration.XlCall)
(import ClojureExcel.MainClass)

(assembly-load "ExcelApi")
(import NetOffice.ExcelApi.Application)

(require '[clojure.string :as str])
(require '[excel-repl.util :as util])

(def letters "ABCDEFGHIJKLMNOPQRSTUVWXYZ")
(def letter->val (into {} (map-indexed (fn [i s] [s i]) letters)))

(defn letter->val2
  "column number of excel coumn A, AZ etc"
  [[s t :as ss]]
  (if t
    (apply + 26
           (map *
                (map letter->val (reverse ss))
                (map #(Math/Pow 26 %) (range))))
    (letter->val s)))

(defn col-num
  "column number of reference in form A4 etc"
  [s]
  (letter->val2 (re-find #"[A-Z]+" s)))

(defn row-num [s]
  (dec (int (re-find #"[0-9]+" s))))

(defn insert-value
  "Inserts val at ref."
  [sheet ref val]
  (let [
        i (row-num ref)
        j (col-num ref)
        ref (ExcelReference. i i j j sheet)
        ]
    (.SetValue ref val)))

(defn split-str [s]
  (map #(str "\"" (apply str %) "\"") (partition-all 250 s)))

(defn concatenated-str [s]
  (format "CONCATENATE(%s)" (util/comma-interpose (split-str s))))

(defn excel-pr-str [s]
  (if (string? s) (concatenated-str (.Replace s "\"" "\"\"")) s))

(defn formula-str [f & args]
  (format "=%s(%s)" f (util/comma-interpose (map excel-pr-str args))))


(defn regularize-array
  "ensures array is rectangular"
  [arr]
  (let [
        n (apply max (map count arr))
        extend #(take n (concat % (repeat "")))
        ]
    (map extend arr)))

(defn insert-values
  "Inserts 2d array of values at ref."
  [sheet ref values]
  (let [
        values (regularize-array values)
        m (count values)
        n (count (first values))
        values (-> values to-array-2d MainClass/RectangularArray)
        i (row-num ref)
        j (col-num ref)
        id (+ i m -1)
        jd (+ j n -1)
        ref (ExcelReference. i id j jd sheet)
        ]
    (-> ref (.SetValue values))))

(defn get-values
  "Returns values at ref which is of the form A1 or A1:B6.
  Single cell selections are returned as a value, 2D selections as an Object[][] array"
  [sheet ref]
  (let [
        refs (if (.Contains ref ":") (str/split ref #":") [ref ref])
        [i id] (map row-num refs)
        [j jd] (map col-num refs)
        ref (ExcelReference. i id j jd sheet)
        ]
    (-> ref .GetValue MainClass/RaggedArray)))

(defn insert-formula
  "Takes a single formula and inserts it into one or many cells.
  Use this instead of insert-values when you have a formula.
  Because Excel-REPL abuses threads the formulas may be stale when first inserted.
  "
  [sheet ref formula]
  (let [
        refs (if (.Contains ref ":") (str/split ref #":") [ref ref])
        [i id] (map row-num refs)
        [j jd] (map col-num refs)
        ref (ExcelReference. i id j jd sheet)
        ]
    (XlCall/Excel XlCall/xlcFormulaFill (object-array [formula ref]))))


(defn add-sheet
  "Adds new sheet to current workbook."
  [name]
  (let [
        sheets (-> (Application/GetActiveInstance) .ActiveWorkbook .Worksheets)
        existing-names (set (map #(.Name %) sheets))
        name (if (existing-names name)
               (loop [i 1]
                 (let [new-name (format "%s (%s)" name i)]
                   (if (existing-names new-name)
                     (recur (inc i))
                     new-name))) name)
        sheet (.Add sheets)
        ]
    (set! (.Name sheet) name)))

(defn get-some [f s]
  (some #(if (f %) %) s))

#_(defn remove-current-sheet
    "Removes current sheet.  Careful!"
    []
    (-> (Application/GetActiveInstance) .ActiveSheet .Delete));prompts user.  probably a bit dangerous anyway
