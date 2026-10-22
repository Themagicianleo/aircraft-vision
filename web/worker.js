// Runs the model off the main thread, so the page stays smooth (and the sparkles keep moving).
import * as ort from "./ort/ort.wasm.min.mjs";

ort.env.wasm.wasmPaths = new URL("./ort/", import.meta.url).href;
ort.env.wasm.numThreads = 1; // threads need cross-origin isolation, which GitHub Pages can't enable

let session, inputName, outputName;

self.onmessage = async (event) => {
  const { id, type } = event.data;
  try {
    if (type === "load") {
      session = await ort.InferenceSession.create(new Uint8Array(event.data.model), {
        executionProviders: ["wasm"],
        graphOptimizationLevel: "all",
      });
      [inputName] = session.inputNames;
      [outputName] = session.outputNames;
      self.postMessage({ id, ok: true });
    } else if (type === "run") {
      const { input, size } = event.data;
      const out = await session.run({ [inputName]: new ort.Tensor("float32", input, [1, 3, size, size]) });
      const probs = Float32Array.from(out[outputName].data);
      self.postMessage({ id, ok: true, probs }, [probs.buffer]);
    }
  } catch (err) {
    self.postMessage({ id, ok: false, error: String((err && err.message) || err) });
  }
};
